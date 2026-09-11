package de.tum.cit.aet.apollon.puml

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun typeOf(model: JsonObject): String? = (model["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content

data class DispatchedExport(val text: String, val residual: PumlResidual)

/**
 * The Apollon-model -> `.puml` text counterpart of [PlantUmlDiagramImporter]: dispatches on the
 * model's own `type` field (set once at import time and never changed by canvas edits) to the
 * matching family's `toPumlDiagram` + exporter, and folds the freshly recomputed
 * `typeKeywords`/`arrowTokens`/`elementAliases` back into [residual] so the *next* sync starts from
 * up-to-date per-id source facts (mirrors [de.tum.cit.aet.apollon.workspace.ArchitectStudioWorkspace.syncToSource]'s
 * pre-dispatch Class-only version of this same fold).
 */
object PlantUmlDiagramExporter {
    fun render(
        model: JsonObject,
        residual: PumlResidual,
    ): DispatchedExport =
        when (typeOf(model)) {
            "ObjectDiagram" -> {
                val export = ObjectModelMapper.toPumlDiagram(model, residual)
                val pruned = residual.copy(typeKeywords = export.typeKeywords, arrowTokens = export.arrowTokens)
                DispatchedExport(PlantUmlObjectExporter.render(export.diagram, pruned), pruned)
            }
            "UseCaseDiagram" -> {
                val export = UseCaseModelMapper.toPumlDiagram(model, residual)
                val pruned = residual.copy(typeKeywords = export.typeKeywords, arrowTokens = export.arrowTokens, elementAliases = export.elementAliases)
                DispatchedExport(PlantUmlUseCaseExporter.render(export.diagram, pruned), pruned)
            }
            "ComponentDiagram" -> {
                val export = ComponentModelMapper.toPumlDiagram(model, residual)
                val pruned = residual.copy(typeKeywords = export.typeKeywords, arrowTokens = export.arrowTokens, elementAliases = export.elementAliases)
                DispatchedExport(PlantUmlComponentExporter.render(export.diagram, pruned), pruned)
            }
            "DeploymentDiagram" -> {
                val export = DeploymentModelMapper.toPumlDiagram(model, residual)
                val pruned = residual.copy(typeKeywords = export.typeKeywords, arrowTokens = export.arrowTokens, elementAliases = export.elementAliases)
                DispatchedExport(PlantUmlDeploymentExporter.render(export.diagram, pruned), pruned)
            }
            else -> {
                val export = ApollonModelMapper.toPumlDiagram(model, residual)
                val pruned = residual.copy(typeKeywords = export.typeKeywords, arrowTokens = export.arrowTokens)
                DispatchedExport(PlantUmlExporter.render(export.diagram, pruned), pruned)
            }
        }
}
