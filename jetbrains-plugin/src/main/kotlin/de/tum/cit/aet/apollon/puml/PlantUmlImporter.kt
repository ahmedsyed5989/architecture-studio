package de.tum.cit.aet.apollon.puml

/**
 * `.puml` text -> [PumlDiagram] + [PumlResidual]. Pure Kotlin, no `com.intellij` import —
 * unit-testable without an IDE (spec §9).
 *
 * Deliberately conservative: PlantUML syntax this parser is not certain about is never guessed
 * at. It is preserved verbatim in [PumlResidual.unsupported] instead (spec §11) rather than risk
 * silently misinterpreting it. See the implementation plan §D for the exact grammar this
 * implements and §A11 for why this is hand-rolled rather than built on a PlantUML library.
 */
sealed interface PumlParseResult {
    data class Rejected(val reason: String) : PumlParseResult

    data class Parsed(val diagram: PumlDiagram, val residual: PumlResidual, val unsupportedCount: Int) : PumlParseResult
}

private val TYPE_DECL =
    Regex(
        """^(abstract\s+class|abstract|class|interface|enum|entity)\s+(?:"([^"]+)"|([A-Za-z_][\w.$]*))(\s+as\s+\S+)?(\s*<<[^>]*>>)?\s*(\{)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

private val IDENT = Regex("""[A-Za-z_][\w.$]*""")
private val SEPARATOR_LINE = Regex("""^[-.=_]{2,}.*""")
private val TOKEN = Regex(""""[^"]*"|\S+""")

private val TIER_A_PREFIXES =
    listOf(
        "skinparam", "!include", "!theme", "!pragma", "!define", "title", "header", "footer",
        "hide ", "show ", "scale ", "left to right direction", "top to bottom direction", "allowmixing",
    )

private val NON_CLASS_MARKERS =
    listOf("@startmindmap", "@startgantt", "@startsalt", "@startwbs", "@startjson", "@startyaml")

object PlantUmlImporter {
    fun parse(text: String): PumlParseResult {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(Regex("\r\n|\n"))
        val startIdx = lines.indexOfFirst { it.trim().lowercase().startsWith("@startuml") }
        val endIdx = lines.indexOfLast { it.trim().lowercase().startsWith("@enduml") }
        if (startIdx < 0 || endIdx < 0 || endIdx <= startIdx) {
            return PumlParseResult.Rejected("does not contain an @startuml block")
        }
        val startLine = lines[startIdx].trim()
        val endLine = lines[endIdx].trim()
        val name = startLine.removePrefix("@startuml").trim().ifEmpty { null }
        val body = lines.subList(startIdx + 1, endIdx)

        if (looksNonClass(body)) {
            return PumlParseResult.Rejected("Architect Studio can currently edit PlantUML class diagrams only")
        }

        val indent =
            body.firstNotNullOfOrNull { line -> Regex("^([ \t]+)\\S").find(line)?.groupValues?.get(1) } ?: "  "

        val types = mutableListOf<PumlType>()
        val relations = mutableListOf<PumlRelation>()
        val preamble = mutableListOf<String>()
        val postamble = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        var sawMapped = false

        var openType: OpenType? = null
        var openBlockCloser: String? = null

        fun flushPostambleAsUnsupported() {
            if (postamble.isNotEmpty()) {
                unsupported += postamble
                postamble.clear()
            }
        }

        for (raw in body) {
            val trimmed = raw.trim()

            if (openType != null) {
                when {
                    trimmed == "}" -> {
                        types += openType.toPumlType()
                        openType = null
                    }
                    trimmed.isEmpty() -> {}
                    SEPARATOR_LINE.matches(trimmed) -> unsupported += raw
                    else -> {
                        val member = parseMemberText(trimmed)
                        val apollonMember = PumlMember(member.toApollonName(), member.isMethod, member.isAbstractModifier)
                        if (member.isMethod) openType.methods += apollonMember else openType.attributes += apollonMember
                    }
                }
                continue
            }

            if (openBlockCloser != null) {
                unsupported += raw
                if (trimmed.equals(openBlockCloser, ignoreCase = true)) {
                    openBlockCloser = null
                }
                continue
            }

            if (trimmed.isEmpty() || isTierA(trimmed)) {
                if (!sawMapped) preamble += raw else postamble += raw
                continue
            }

            val typeMatch = TYPE_DECL.find(trimmed)
            if (typeMatch != null) {
                flushPostambleAsUnsupported()
                val keyword = typeMatch.groupValues[1].lowercase().replace(Regex("\\s+"), " ")
                val name0 = typeMatch.groupValues[2].ifEmpty { typeMatch.groupValues[3] }
                val hasAlias = typeMatch.groupValues[4].isNotEmpty()
                val hasStereotype = typeMatch.groupValues[5].isNotEmpty()
                val opensBody = typeMatch.groupValues[6] == "{"
                if (hasAlias || hasStereotype) {
                    // Aliasing and explicit <<stereotypes>> are out of scope (plan §D1) —
                    // preserved verbatim rather than misrepresented.
                    flushPostambleAsUnsupported()
                    unsupported += raw
                    if (opensBody) openBlockCloser = "}"
                    continue
                }
                sawMapped = true
                val kind =
                    when (keyword) {
                        "abstract class", "abstract" -> PumlKind.ABSTRACT_CLASS
                        "interface" -> PumlKind.INTERFACE
                        "enum" -> PumlKind.ENUM
                        "entity" -> PumlKind.ENTITY
                        else -> PumlKind.CLASS
                    }
                if (opensBody) {
                    openType = OpenType(name0, kind, keyword)
                } else {
                    types += PumlType(name0, kind, emptyList(), emptyList(), keyword)
                }
                continue
            }

            val relation = tryParseRelation(trimmed)
            if (relation != null) {
                flushPostambleAsUnsupported()
                sawMapped = true
                relations += relation
                continue
            }

            val lower = trimmed.lowercase()
            if (lower.startsWith("note") && !trimmed.contains(":")) {
                flushPostambleAsUnsupported()
                unsupported += raw
                openBlockCloser = "end note"
                continue
            }
            if ((lower.startsWith("package ") || lower.startsWith("namespace ") || lower.startsWith("together")) &&
                trimmed.endsWith("{")
            ) {
                flushPostambleAsUnsupported()
                unsupported += raw
                openBlockCloser = "}"
                continue
            }

            flushPostambleAsUnsupported()
            unsupported += raw
        }

        openType?.let { unsupported += "' Architect Studio: unterminated ${it.keyword} ${it.name}" }

        val residual =
            PumlResidual(
                startLine = startLine,
                endLine = endLine,
                eol = eol,
                indent = indent,
                preamble = preamble,
                postamble = postamble,
                unsupported = unsupported,
            )
        val diagram = PumlDiagram(name, types, relations)
        return PumlParseResult.Parsed(diagram, residual, unsupported.count { it.isNotBlank() })
    }

    private fun looksNonClass(body: List<String>): Boolean {
        if (body.any { line -> NON_CLASS_MARKERS.any { line.trim().lowercase().startsWith(it) } }) return true
        return body.any { line ->
            val t = line.trim().lowercase()
            t.startsWith("participant ") || t.startsWith("actor ") || t.startsWith("usecase ")
        }
    }

    private fun isTierA(trimmed: String): Boolean {
        if (trimmed.startsWith("'")) return true
        val lower = trimmed.lowercase()
        return TIER_A_PREFIXES.any { lower.startsWith(it) }
    }

    private fun isQuoted(token: String) = token.length >= 2 && token.startsWith("\"") && token.endsWith("\"")

    private fun unquote(token: String) = if (isQuoted(token)) token.substring(1, token.length - 1) else token

    private fun tryParseRelation(line: String): PumlRelation? {
        val tokens = TOKEN.findAll(line).map { it.value }.toList()
        val arrowIndices = tokens.indices.filter { classifyArrow(tokens[it]) != null }
        if (arrowIndices.size != 1) return null
        val arrowIdx = arrowIndices[0]
        if (arrowIdx <= 0 || arrowIdx >= tokens.size - 1) return null
        val info = classifyArrow(tokens[arrowIdx]) ?: return null

        val leftTokens = tokens.subList(0, arrowIdx)
        if (leftTokens.isEmpty() || leftTokens.size > 2) return null
        val leftName = leftTokens[0]
        val leftLabelToken = leftTokens.getOrNull(1)
        if (leftLabelToken != null && !isQuoted(leftLabelToken)) return null

        val rightAll = tokens.subList(arrowIdx + 1, tokens.size)
        val colonIdx = rightAll.indexOf(":")
        val rightSide = if (colonIdx >= 0) rightAll.subList(0, colonIdx) else rightAll
        val trailing = if (colonIdx >= 0) rightAll.subList(colonIdx + 1, rightAll.size) else emptyList()
        if (rightSide.isEmpty() || rightSide.size > 2) return null
        // Unlike the left side (`Name "label"`), PlantUML puts the right side's label BEFORE its
        // class name (`"label" Name`) — mirror-imaged around the arrow.
        val rightLabelToken = if (rightSide.size == 2) rightSide[0] else null
        val rightName = rightSide.last()
        if (rightLabelToken != null && !isQuoted(rightLabelToken)) return null

        if (isQuoted(leftName) || isQuoted(rightName)) return null
        if (!IDENT.matches(leftName) || !IDENT.matches(rightName)) return null

        val (leftMult, leftRole) = splitEndLabel(leftLabelToken?.let { unquote(it) } ?: "")
        val (rightMult, rightRole) = splitEndLabel(rightLabelToken?.let { unquote(it) } ?: "")
        val label = trailing.joinToString(" ")

        val sourceName: String
        val targetName: String
        val sourceMult: String
        val sourceRole: String
        val targetMult: String
        val targetRole: String
        when (info.decoratedSide) {
            Side.LEFT -> {
                targetName = leftName
                sourceName = rightName
                targetMult = leftMult
                targetRole = leftRole
                sourceMult = rightMult
                sourceRole = rightRole
            }
            Side.RIGHT -> {
                targetName = rightName
                sourceName = leftName
                targetMult = rightMult
                targetRole = rightRole
                sourceMult = leftMult
                sourceRole = leftRole
            }
            Side.NONE -> {
                sourceName = leftName
                targetName = rightName
                sourceMult = leftMult
                sourceRole = leftRole
                targetMult = rightMult
                targetRole = rightRole
            }
        }
        return PumlRelation(sourceName, targetName, info.kind, sourceMult, sourceRole, targetMult, targetRole, label, tokens[arrowIdx])
    }
}

private data class OpenType(
    val name: String,
    val kind: PumlKind,
    val keyword: String,
    val attributes: MutableList<PumlMember> = mutableListOf(),
    val methods: MutableList<PumlMember> = mutableListOf(),
) {
    fun toPumlType() = PumlType(name, kind, attributes.toList(), methods.toList(), keyword)
}

