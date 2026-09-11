package de.tum.cit.aet.apollon.puml

private val ACTOR_DECL = declarationRegex("actor")
private val USE_CASE_DECL = declarationRegex("usecase")
private val SYSTEM_DECL = declarationRegex("rectangle")

sealed interface PumlUseCaseParseResult {
    data class Rejected(val reason: String) : PumlUseCaseParseResult

    data class Parsed(val diagram: PumlUseCaseDiagram, val residual: PumlResidual, val unsupportedCount: Int) : PumlUseCaseParseResult
}

/**
 * `.puml` text -> [PumlUseCaseDiagram] + [PumlResidual]. Only the keyword forms `actor X`/`usecase X`
 * are understood — PlantUML's `:Actor:`/`(Use case)` shorthand notations are not (plan §9's scoping
 * call: [PlantUmlUseCaseExporter] always emits keyword form too, so this is a closed, stable round
 * trip rather than a partial read of a wider grammar). System boundaries
 * (`rectangle "Name" as id { ... }`) nest one level deep; a rectangle declared inside another
 * rectangle — or any other unrecognized brace-opening line — is preserved verbatim, braces and all,
 * instead of guessed at (see the `unsupportedDepth` brace counter below).
 */
object PlantUmlUseCaseImporter {
    fun parse(text: String): PumlUseCaseParseResult {
        val envelope = PumlEnvelopeReader.read(text) ?: return PumlUseCaseParseResult.Rejected("does not contain an @startuml block")
        val name = envelope.startLine.removePrefix("@startuml").trim().ifEmpty { null }

        val elements = mutableListOf<PumlUseCaseElement>()
        val relations = mutableListOf<PumlUseCaseRelation>()
        val preamble = mutableListOf<String>()
        val postamble = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        var sawMapped = false

        var openContainer: String? = null
        var unsupportedDepth = 0

        fun flushPostambleAsUnsupported() {
            if (postamble.isNotEmpty()) {
                unsupported += postamble
                postamble.clear()
            }
        }

        fun refIdOf(decl: ParsedDeclaration): String = decl.alias ?: decl.displayName

        for (raw in envelope.body) {
            val trimmed = raw.trim()

            if (unsupportedDepth > 0) {
                unsupported += raw
                if (trimmed.endsWith("{")) unsupportedDepth++
                if (trimmed == "}" || trimmed.endsWith("}")) unsupportedDepth--
                continue
            }

            if (openContainer != null && trimmed == "}") {
                openContainer = null
                continue
            }

            if (trimmed.isEmpty() || isTierALine(trimmed)) {
                if (!sawMapped) preamble += raw else postamble += raw
                continue
            }

            val actorMatch = matchDeclaration(ACTOR_DECL, trimmed)
            if (actorMatch != null) {
                flushPostambleAsUnsupported()
                sawMapped = true
                elements +=
                    PumlUseCaseElement(refIdOf(actorMatch), actorMatch.displayName, actorMatch.alias, PumlUseCaseElementKind.ACTOR, openContainer)
                if (actorMatch.opensBody) unsupportedDepth++
                continue
            }

            val useCaseMatch = matchDeclaration(USE_CASE_DECL, trimmed)
            if (useCaseMatch != null) {
                flushPostambleAsUnsupported()
                sawMapped = true
                elements +=
                    PumlUseCaseElement(
                        refIdOf(useCaseMatch),
                        useCaseMatch.displayName,
                        useCaseMatch.alias,
                        PumlUseCaseElementKind.USE_CASE,
                        openContainer,
                    )
                if (useCaseMatch.opensBody) unsupportedDepth++
                continue
            }

            val systemMatch = matchDeclaration(SYSTEM_DECL, trimmed)
            if (systemMatch != null) {
                if (openContainer != null) {
                    flushPostambleAsUnsupported()
                    unsupported += raw
                    if (systemMatch.opensBody) unsupportedDepth++
                    continue
                }
                flushPostambleAsUnsupported()
                sawMapped = true
                val refId = refIdOf(systemMatch)
                elements += PumlUseCaseElement(refId, systemMatch.displayName, systemMatch.alias, PumlUseCaseElementKind.SYSTEM, null)
                if (systemMatch.opensBody) openContainer = refId
                continue
            }

            val generic = PumlRelationGrammar.parseRelationLine(trimmed)
            val classified = generic?.let { classifyUseCaseRelation(it) }
            if (classified != null) {
                flushPostambleAsUnsupported()
                sawMapped = true
                relations += classified
                continue
            }

            flushPostambleAsUnsupported()
            unsupported += raw
            if (trimmed.endsWith("{")) unsupportedDepth++
        }

        if (openContainer != null) unsupported += "' Architect Studio: unterminated rectangle $openContainer"

        if (!sawMapped) return PumlUseCaseParseResult.Rejected("no actor/usecase declarations found")

        val residual =
            PumlResidual(
                startLine = envelope.startLine,
                endLine = envelope.endLine,
                eol = envelope.eol,
                indent = envelope.indent,
                preamble = preamble,
                postamble = postamble,
                unsupported = unsupported,
            )
        return PumlUseCaseParseResult.Parsed(PumlUseCaseDiagram(name, elements, relations), residual, unsupported.count { it.isNotBlank() })
    }
}
