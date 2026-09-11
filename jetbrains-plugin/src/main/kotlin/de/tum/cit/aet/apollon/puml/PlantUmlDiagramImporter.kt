package de.tum.cit.aet.apollon.puml

import kotlinx.serialization.json.JsonObject

/** What every family's [PumlParseResult.Parsed]/[PumlObjectParseResult.Parsed]/... converges to
 *  once [PlantUmlDiagramImporter] has picked a family and parsed it — everything
 *  [de.tum.cit.aet.apollon.workspace.ArchitectStudioWorkspace] needs without knowing which family
 *  produced it. */
sealed interface DispatchedImport {
    data class Rejected(val reason: String) : DispatchedImport

    data class Parsed(
        val family: DiagramFamily,
        val residual: PumlResidual,
        val unsupportedCount: Int,
        val toApollonModel: (previous: JsonObject?, title: String) -> MappedModel,
    ) : DispatchedImport
}

/**
 * The single `.puml` text -> Apollon-model entry point spanning every family this plugin can
 * visually edit (plan §9): runs [DiagramTypeDetector], then hands off to that family's own
 * importer/mapper pair. A family the detector recognises but this plugin has no importer for
 * (Activity/Sequence/State/... — plan §21's "target, not implemented" set) is rejected with a
 * specific, honest reason rather than silently falling through to the Class parser and
 * misinterpreting it.
 */
object PlantUmlDiagramImporter {
    fun parse(text: String): DispatchedImport {
        val family = DiagramTypeDetector.detect(text)
        if (!family.hasImporter) {
            return DispatchedImport.Rejected(unsupportedReasonFor(family))
        }
        return when (family) {
            DiagramFamily.CLASS ->
                when (val r = PlantUmlImporter.parse(text)) {
                    is PumlParseResult.Rejected -> DispatchedImport.Rejected(r.reason)
                    is PumlParseResult.Parsed ->
                        DispatchedImport.Parsed(family, r.residual, r.unsupportedCount) { prev, title ->
                            ApollonModelMapper.toApollonModel(r.diagram, prev, title)
                        }
                }
            DiagramFamily.OBJECT ->
                when (val r = PlantUmlObjectImporter.parse(text)) {
                    is PumlObjectParseResult.Rejected -> DispatchedImport.Rejected(r.reason)
                    is PumlObjectParseResult.Parsed ->
                        DispatchedImport.Parsed(family, r.residual, r.unsupportedCount) { prev, title ->
                            ObjectModelMapper.toApollonModel(r.diagram, prev, title)
                        }
                }
            DiagramFamily.USE_CASE ->
                when (val r = PlantUmlUseCaseImporter.parse(text)) {
                    is PumlUseCaseParseResult.Rejected -> DispatchedImport.Rejected(r.reason)
                    is PumlUseCaseParseResult.Parsed ->
                        DispatchedImport.Parsed(family, r.residual, r.unsupportedCount) { prev, title ->
                            UseCaseModelMapper.toApollonModel(r.diagram, prev, title)
                        }
                }
            DiagramFamily.COMPONENT ->
                when (val r = PlantUmlComponentImporter.parse(text)) {
                    is PumlComponentParseResult.Rejected -> DispatchedImport.Rejected(r.reason)
                    is PumlComponentParseResult.Parsed ->
                        DispatchedImport.Parsed(family, r.residual, r.unsupportedCount) { prev, title ->
                            ComponentModelMapper.toApollonModel(r.diagram, prev, title)
                        }
                }
            DiagramFamily.DEPLOYMENT ->
                when (val r = PlantUmlDeploymentImporter.parse(text)) {
                    is PumlDeploymentParseResult.Rejected -> DispatchedImport.Rejected(r.reason)
                    is PumlDeploymentParseResult.Parsed ->
                        DispatchedImport.Parsed(family, r.residual, r.unsupportedCount) { prev, title ->
                            DeploymentModelMapper.toApollonModel(r.diagram, prev, title)
                        }
                }
            else -> DispatchedImport.Rejected(unsupportedReasonFor(family))
        }
    }

    private fun unsupportedReasonFor(family: DiagramFamily): String =
        when (family) {
            DiagramFamily.UNKNOWN -> "does not contain an @startuml block"
            DiagramFamily.C4 -> "is a C4-PlantUML diagram — Architect Studio can render it in Preview but cannot yet edit it visually"
            DiagramFamily.ACTIVITY -> "is an Activity diagram — Architect Studio can render it in Preview but cannot yet edit it visually"
            DiagramFamily.COMMUNICATION -> "is a Communication diagram — Architect Studio can render it in Preview but cannot yet edit it visually"
            DiagramFamily.SEQUENCE -> "is a Sequence diagram — Architect Studio can render it in Preview but cannot yet edit it visually"
            DiagramFamily.STATE -> "is a State diagram — Architect Studio can render it in Preview but cannot yet edit it visually"
            DiagramFamily.OTHER -> "is a PlantUML diagram type Architect Studio does not yet recognise"
            else -> "is not a diagram type Architect Studio can edit visually"
        }
}
