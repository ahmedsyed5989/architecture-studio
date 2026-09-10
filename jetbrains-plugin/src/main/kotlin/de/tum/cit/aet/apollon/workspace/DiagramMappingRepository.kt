package de.tum.cit.aet.apollon.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * CRUD over `<workspaceRoot>/index.json`. Pure `java.nio.file`, no IDE types — unit-testable with
 * a plain temp directory (spec §9). [root] is the `.architect-studio/` directory itself.
 *
 * A corrupt file is backed up to `index.json.bak` and replaced with an empty index rather than
 * blocking the user forever; a *newer-schema* file is left untouched and the constructor throws
 * [UnsupportedIndexVersionException] instead, so the caller can refuse the operation and tell the
 * user why.
 */
class DiagramMappingRepository(private val root: Path) {
    private val indexPath: Path = root.resolve("index.json")

    private var index: DiagramIndex = load()

    private fun load(): DiagramIndex {
        if (!Files.exists(indexPath)) return DiagramIndexCodec.empty()
        val text = Files.readString(indexPath)
        return try {
            DiagramIndexCodec.parse(text)
        } catch (e: CorruptIndexException) {
            val backup = root.resolve("index.json.bak")
            runCatching { Files.copy(indexPath, backup, StandardCopyOption.REPLACE_EXISTING) }
            DiagramIndexCodec.empty()
        }
    }

    fun all(): List<DiagramEntry> = index.diagrams

    fun findBySource(relativeSourcePath: String): DiagramEntry? = index.diagrams.find { it.source == relativeSourcePath }

    fun findByWorkingFile(relativeWorkingPath: String): DiagramEntry? = index.diagrams.find { it.workingFile == relativeWorkingPath }

    fun findById(id: String): DiagramEntry? = index.diagrams.find { it.id == id }

    fun upsert(entry: DiagramEntry) {
        index = DiagramIndex(index.version, index.diagrams.filterNot { it.id == entry.id } + entry)
    }

    fun remove(id: String) {
        index = DiagramIndex(index.version, index.diagrams.filterNot { it.id == id })
    }

    fun save() {
        writeAtomically(indexPath, DiagramIndexCodec.render(index))
    }
}
