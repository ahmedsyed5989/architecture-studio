package de.tum.cit.aet.apollon.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes [text] to [target] via a temp file + atomic rename, so a crash mid-write can never
 * leave a half-written internal artefact — the precedent already set by
 * `export/DiagramExporter.kt`'s sibling-image write, made atomic (spec §12). For files the user
 * edits directly (the `.puml` source, `.gitignore`), the workspace instead goes through the IDE's
 * `Document` layer (see `ArchitectStudioWorkspace`); this helper is only for internal artefacts
 * under `.architect-studio` that the user never opens directly.
 */
fun writeAtomically(
    target: Path,
    text: String,
) {
    Files.createDirectories(target.parent)
    val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
    try {
        Files.writeString(tmp, text, StandardCharsets.UTF_8)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}
