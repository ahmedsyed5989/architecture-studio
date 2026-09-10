package de.tum.cit.aet.apollon.puml

import kotlinx.serialization.json.JsonObject

/**
 * The gate a candidate `.puml` rewrite must pass before Architect Studio ever touches the real
 * source file (plan §A9 / spec §12): re-parse it and check the result still agrees with the
 * model it was generated from. A failure means the exporter and the model have drifted —
 * something this converter does not understand well enough to trust — so the caller must refuse
 * the write rather than risk a lossy rewrite.
 */
object RoundTripValidator {
    fun validate(
        candidate: String,
        model: JsonObject,
    ): String? {
        if (!candidate.contains("@startuml") || !candidate.contains("@enduml")) {
            return "the generated PlantUML is missing @startuml/@enduml"
        }
        val reparsed = PlantUmlImporter.parse(candidate)
        if (reparsed is PumlParseResult.Rejected) {
            return "the generated PlantUML failed to re-parse: ${reparsed.reason}"
        }
        reparsed as PumlParseResult.Parsed

        val (expectedNames, expectedRelations) = ApollonModelMapper.signature(model)
        val actualNames = reparsed.diagram.types.map { it.name }.toSet()
        if (actualNames != expectedNames) {
            return "the generated PlantUML's classes do not match the diagram (expected $expectedNames, got $actualNames)"
        }

        val actualRelations =
            reparsed.diagram.relations.map { Triple(it.sourceName, it.targetName, ApollonModelMapper.apollonEdgeType(it.kind)) }
        val expectedMultiset = expectedRelations.groupingBy { it }.eachCount()
        val actualMultiset = actualRelations.groupingBy { it }.eachCount()
        if (expectedMultiset != actualMultiset) {
            return "the generated PlantUML's relationships do not match the diagram"
        }
        return null
    }
}
