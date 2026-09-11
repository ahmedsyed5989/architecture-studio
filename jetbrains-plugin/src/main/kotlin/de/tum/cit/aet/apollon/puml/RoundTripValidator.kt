package de.tum.cit.aet.apollon.puml

import kotlinx.serialization.json.JsonObject

/**
 * The gate a candidate `.puml` rewrite must pass before Architect Studio ever touches the real
 * source file (plan §A9 / spec §12): re-parse it and check the result still agrees with the
 * model it was generated from. A failure means the exporter and the model have drifted —
 * something this converter does not understand well enough to trust — so the caller must refuse
 * the write rather than risk a lossy rewrite.
 *
 * Dispatches on the Apollon model's own `type` field (plan §9) — each family importer/mapper pair
 * is re-verified with its own grammar rather than through one generic re-parse, the same
 * "kept in sync by hand, not refactored into one shared abstraction" choice
 * [PumlRelationGrammar]'s doc comment explains for the parsing side.
 */
object RoundTripValidator {
    fun validate(
        candidate: String,
        model: JsonObject,
    ): String? {
        if (!candidate.contains("@startuml") || !candidate.contains("@enduml")) {
            return "the generated PlantUML is missing @startuml/@enduml"
        }
        return when (textOf(model["type"])) {
            "ObjectDiagram" -> validateObject(candidate, model)
            "UseCaseDiagram" -> validateUseCase(candidate, model)
            "ComponentDiagram" -> validateComponent(candidate, model)
            "DeploymentDiagram" -> validateDeployment(candidate, model)
            else -> validateClass(candidate, model)
        }
    }

    private fun validateClass(
        candidate: String,
        model: JsonObject,
    ): String? {
        val reparsed = PlantUmlImporter.parse(candidate)
        if (reparsed is PumlParseResult.Rejected) return "the generated PlantUML failed to re-parse: ${reparsed.reason}"
        reparsed as PumlParseResult.Parsed

        val (expectedNames, expectedRelations) = ApollonModelMapper.signature(model)
        val actualNames = reparsed.diagram.types.map { it.name }.toSet()
        if (actualNames != expectedNames) {
            return "the generated PlantUML's classes do not match the diagram (expected $expectedNames, got $actualNames)"
        }
        val actualRelations =
            reparsed.diagram.relations.map { Triple(it.sourceName, it.targetName, ApollonModelMapper.apollonEdgeType(it.kind)) }
        return compareMultisets(expectedRelations, actualRelations)
    }

    private fun validateObject(
        candidate: String,
        model: JsonObject,
    ): String? {
        val reparsed = PlantUmlObjectImporter.parse(candidate)
        if (reparsed is PumlObjectParseResult.Rejected) return "the generated PlantUML failed to re-parse: ${reparsed.reason}"
        reparsed as PumlObjectParseResult.Parsed

        val (expectedNames, expectedRelations) = ObjectModelMapper.signature(model)
        val actualNames = reparsed.diagram.objects.map { it.name }.toSet()
        if (actualNames != expectedNames) {
            return "the generated PlantUML's objects do not match the diagram (expected $expectedNames, got $actualNames)"
        }
        val actualRelations = reparsed.diagram.relations.map { Triple(it.sourceName, it.targetName, "ObjectLink") }
        return compareMultisets(expectedRelations, actualRelations)
    }

    private fun validateUseCase(
        candidate: String,
        model: JsonObject,
    ): String? {
        val reparsed = PlantUmlUseCaseImporter.parse(candidate)
        if (reparsed is PumlUseCaseParseResult.Rejected) return "the generated PlantUML failed to re-parse: ${reparsed.reason}"
        reparsed as PumlUseCaseParseResult.Parsed

        val (expectedNames, expectedRelations) = UseCaseModelMapper.signature(model)
        val actualNames = reparsed.diagram.elements.map { it.displayName }.toSet()
        if (actualNames != expectedNames) {
            return "the generated PlantUML's elements do not match the diagram (expected $expectedNames, got $actualNames)"
        }
        val refToName = reparsed.diagram.elements.associate { it.refId to it.displayName }
        val actualRelations =
            reparsed.diagram.relations.mapNotNull { rel ->
                val src = refToName[rel.sourceRefId] ?: return@mapNotNull null
                val tgt = refToName[rel.targetRefId] ?: return@mapNotNull null
                Triple(src, tgt, useCaseEdgeType(rel.kind))
            }
        return compareMultisets(expectedRelations, actualRelations)
    }

    private fun validateComponent(
        candidate: String,
        model: JsonObject,
    ): String? {
        val reparsed = PlantUmlComponentImporter.parse(candidate)
        if (reparsed is PumlComponentParseResult.Rejected) return "the generated PlantUML failed to re-parse: ${reparsed.reason}"
        reparsed as PumlComponentParseResult.Parsed

        val (expectedNames, expectedRelations) = ComponentModelMapper.signature(model)
        val actualNames = reparsed.diagram.elements.map { it.displayName }.toSet()
        if (actualNames != expectedNames) {
            return "the generated PlantUML's elements do not match the diagram (expected $expectedNames, got $actualNames)"
        }
        val refToName = reparsed.diagram.elements.associate { it.refId to it.displayName }
        val actualRelations =
            reparsed.diagram.relations.mapNotNull { rel ->
                val src = refToName[rel.sourceName] ?: return@mapNotNull null
                val tgt = refToName[rel.targetName] ?: return@mapNotNull null
                Triple(src, tgt, "ComponentDependency")
            }
        return compareMultisets(expectedRelations, actualRelations)
    }

    private fun validateDeployment(
        candidate: String,
        model: JsonObject,
    ): String? {
        val reparsed = PlantUmlDeploymentImporter.parse(candidate)
        if (reparsed is PumlDeploymentParseResult.Rejected) return "the generated PlantUML failed to re-parse: ${reparsed.reason}"
        reparsed as PumlDeploymentParseResult.Parsed

        val (expectedNames, expectedRelations) = DeploymentModelMapper.signature(model)
        val actualNames = reparsed.diagram.elements.map { it.displayName }.toSet()
        if (actualNames != expectedNames) {
            return "the generated PlantUML's elements do not match the diagram (expected $expectedNames, got $actualNames)"
        }
        val refToName = reparsed.diagram.elements.associate { it.refId to it.displayName }
        val actualRelations =
            reparsed.diagram.relations.mapNotNull { rel ->
                val src = refToName[rel.sourceRefId] ?: return@mapNotNull null
                val tgt = refToName[rel.targetRefId] ?: return@mapNotNull null
                val type = if (rel.kind == PumlDeploymentRelationKind.ASSOCIATION) "DeploymentAssociation" else "DeploymentDependency"
                Triple(src, tgt, type)
            }
        return compareMultisets(expectedRelations, actualRelations)
    }

    private fun useCaseEdgeType(kind: PumlUseCaseRelationKind): String =
        when (kind) {
            PumlUseCaseRelationKind.ASSOCIATION -> "UseCaseAssociation"
            PumlUseCaseRelationKind.INCLUDE -> "UseCaseInclude"
            PumlUseCaseRelationKind.EXTEND -> "UseCaseExtend"
            PumlUseCaseRelationKind.GENERALIZATION -> "UseCaseGeneralization"
        }

    private fun compareMultisets(
        expected: List<Triple<String, String, String>>,
        actual: List<Triple<String, String, String>>,
    ): String? {
        val expectedMultiset = expected.groupingBy { it }.eachCount()
        val actualMultiset = actual.groupingBy { it }.eachCount()
        if (expectedMultiset != actualMultiset) {
            return "the generated PlantUML's relationships do not match the diagram"
        }
        return null
    }
}
