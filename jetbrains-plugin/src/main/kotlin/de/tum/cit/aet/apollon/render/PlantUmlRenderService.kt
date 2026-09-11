package de.tum.cit.aet.apollon.render

import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import java.io.ByteArrayOutputStream

/**
 * `.puml` text -> SVG, via the bundled `plantuml-mit` engine (plan §11) — the Preview tab's only
 * dependency on PlantUML's own renderer; every other file in `puml/` is this plugin's own
 * hand-rolled grammar and never touches this class. Deliberately family-agnostic: unlike
 * [de.tum.cit.aet.apollon.puml.PlantUmlDiagramImporter], rendering does not require recognizing or
 * understanding the diagram — every family this plugin cannot visually edit yet (Activity, C4,
 * Sequence, State, Communication, ...) still gets a real preview through this path, which is why
 * Preview is a separate tab from the Visual canvas rather than gated behind the same family check.
 *
 * Runs PlantUML's `SANDBOX` [net.sourceforge.plantuml.security.SecurityProfile] — no network
 * access, no reads outside PlantUML's own bundled standard library — set via the
 * `PLANTUML_SECURITY_PROFILE` system property PlantUML reads once at class-init (spec's security
 * requirement: rendering a file must never be a vector to exfiltrate other project files or make
 * network calls the user didn't ask for). [ensureSandboxed] must run before any other class in the
 * `net.sourceforge.plantuml` package is touched for the property to take effect — every entry
 * point in this file calls it first.
 */
object PlantUmlRenderService {
    private const val SECURITY_PROFILE_PROPERTY = "PLANTUML_SECURITY_PROFILE"

    private fun ensureSandboxed() {
        if (System.getProperty(SECURITY_PROFILE_PROPERTY) == null) {
            System.setProperty(SECURITY_PROFILE_PROPERTY, "SANDBOX")
        }
    }

    sealed interface RenderResult {
        data class Rendered(val svg: String) : RenderResult

        data class Failed(val reason: String) : RenderResult
    }

    /** Renders the first `@startuml`/`@enduml` block in [text] to an SVG document. PlantUML draws a
     *  readable in-image error message (rather than throwing) for a diagram it cannot parse, which
     *  is exactly the "show the user what's wrong" behaviour a live preview wants — [RenderResult.Failed]
     *  is reserved for this call itself throwing (a PlantUML engine bug/OOM/etc), not for a syntax
     *  error in [text]. */
    fun render(text: String): RenderResult {
        ensureSandboxed()
        return try {
            val output = ByteArrayOutputStream()
            val reader = SourceStringReader(text)
            reader.outputImage(output, FileFormatOption(FileFormat.SVG))
            val svg = output.toString(Charsets.UTF_8)
            if (svg.isBlank()) RenderResult.Failed("PlantUML produced no output for this file") else RenderResult.Rendered(svg)
        } catch (e: Exception) {
            RenderResult.Failed(e.message ?: "PlantUML failed to render this file")
        }
    }
}
