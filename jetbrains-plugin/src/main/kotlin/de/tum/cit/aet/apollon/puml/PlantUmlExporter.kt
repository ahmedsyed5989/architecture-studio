package de.tum.cit.aet.apollon.puml

private val BARE_IDENT = Regex("""^[A-Za-z_][\w.$]*$""")

/**
 * [PumlDiagram] + [PumlResidual] -> `.puml` text. The exact inverse of [PlantUmlImporter],
 * deterministic (plan §D3/§D4): the same diagram + residual always renders to the same bytes, so
 * `puml -> model -> puml` twice in a row without an edit produces byte-identical output
 * (`RoundTripTest`).
 */
object PlantUmlExporter {
    fun render(
        diagram: PumlDiagram,
        residual: PumlResidual,
    ): String {
        val indent = residual.indent.ifEmpty { "  " }
        val lines = mutableListOf<String>()
        lines += residual.startLine
        lines += residual.preamble
        diagram.types.forEach { lines += renderType(it, indent) }
        diagram.relations.forEach { lines += renderRelation(it) }
        lines += residual.unsupported
        lines += residual.postamble
        lines += residual.endLine
        val eol = residual.eol.ifEmpty { "\n" }
        return lines.joinToString(eol) + eol
    }

    private fun renderType(
        type: PumlType,
        indent: String,
    ): List<String> {
        val header = "${type.keyword} ${quoteIfNeeded(type.name)}"
        if (type.attributes.isEmpty() && type.methods.isEmpty()) {
            return listOf(header)
        }
        val out = mutableListOf("$header {")
        type.attributes.forEach { out += indent + parseMemberText(it.apollonName).toPumlLine(it.isAbstract) }
        type.methods.forEach { out += indent + parseMemberText(it.apollonName).toPumlLine(it.isAbstract) }
        out += "}"
        return out
    }

    private fun renderRelation(rel: PumlRelation): String {
        val token = rel.arrowToken.ifBlank { canonicalArrowToken(rel.kind) }
        val info = classifyArrow(token) ?: classifyArrow(canonicalArrowToken(rel.kind))!!

        var leftName = rel.sourceName
        var rightName = rel.targetName
        var leftMult = rel.sourceMultiplicity
        var leftRole = rel.sourceRole
        var rightMult = rel.targetMultiplicity
        var rightRole = rel.targetRole
        if (info.decoratedSide == Side.LEFT) {
            leftName = rel.targetName
            rightName = rel.sourceName
            leftMult = rel.targetMultiplicity
            leftRole = rel.targetRole
            rightMult = rel.sourceMultiplicity
            rightRole = rel.sourceRole
        }

        val leftLabel = joinEndLabel(leftMult, leftRole)
        val rightLabel = joinEndLabel(rightMult, rightRole)
        val parts = mutableListOf(quoteIfNeeded(leftName))
        if (leftLabel.isNotEmpty()) parts += "\"$leftLabel\""
        parts += token
        if (rightLabel.isNotEmpty()) parts += "\"$rightLabel\""
        parts += quoteIfNeeded(rightName)
        var line = parts.joinToString(" ")
        if (rel.label.isNotEmpty()) line += " : ${rel.label}"
        return line
    }

    private fun quoteIfNeeded(name: String): String = if (BARE_IDENT.matches(name)) name else "\"$name\""
}
