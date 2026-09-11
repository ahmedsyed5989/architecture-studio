package de.tum.cit.aet.apollon.workspace

import com.intellij.openapi.components.Service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.tum.cit.aet.apollon.document.parseModel
import de.tum.cit.aet.apollon.document.writeDocumentText
import de.tum.cit.aet.apollon.puml.DispatchedImport
import de.tum.cit.aet.apollon.puml.PlantUmlDiagramExporter
import de.tum.cit.aet.apollon.puml.PlantUmlDiagramImporter
import de.tum.cit.aet.apollon.puml.PumlResidual
import de.tum.cit.aet.apollon.puml.RoundTripValidator
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

private const val WORKSPACE_DIR_NAME = ".architect-studio"

/** A `.puml` source paired with its working `.apollon` file and index entry. */
data class DiagramBinding(val entry: DiagramEntry, val sourceFile: VirtualFile, val workingFile: VirtualFile)

sealed interface OpenOutcome {
    /** Opened (or ready to open) [workingFile]. [unsupportedCount] is the number of PlantUML lines
     *  that could not be represented on the canvas and were preserved verbatim instead (spec §11);
     *  zero on a plain reopen where nothing changed. */
    data class Opened(val workingFile: VirtualFile, val unsupportedCount: Int) : OpenOutcome

    /** The file is not something Architect Studio can edit (not a class diagram, unsupported alias
     *  syntax at the top level, missing `@startuml`, etc). Not an error — expected for some inputs. */
    data class Rejected(val reason: String) : OpenOutcome

    /** An unexpected failure (IO, corrupt workspace state). */
    data class Failed(val reason: String) : OpenOutcome
}

sealed interface SyncOutcome {
    data object Saved : SyncOutcome

    /** The source hasn't changed on disk since it was last synced, but the canvas didn't produce a
     *  different `.puml` either — nothing was written. */
    data object Unchanged : SyncOutcome

    /** The `.puml` source changed on disk since Architect Studio last read it (spec §13): refuse to
     *  overwrite until the caller has the user decide (re-import and lose canvas changes, or force
     *  overwrite). */
    data class Stale(val binding: DiagramBinding) : SyncOutcome

    data class Failed(val reason: String) : SyncOutcome
}

/**
 * Owns `.architect-studio/` for one project: creating it, keeping `index.json` and `.gitignore`
 * up to date, importing a `.puml` into a working `.apollon` file, and syncing canvas edits back to
 * the original `.puml` on save (spec §3-§8). The only class in the `puml`/`workspace` packages
 * that touches IntelliJ Platform APIs — [de.tum.cit.aet.apollon.puml] and the rest of this package
 * stay pure and unit-testable (spec §9).
 */
@Service(Service.Level.PROJECT)
class ArchitectStudioWorkspace(private val project: Project) {
    fun projectRootPath(): Path? = project.basePath?.let { Path.of(it) }

    private fun workspaceRoot(): Path? = projectRootPath()?.resolve(WORKSPACE_DIR_NAME)

    /** Project-relative, forward-slash path for [file], or `null` if it is outside the project
     *  (spec §4: the index only ever stores project-relative paths). */
    fun relativize(file: VirtualFile): String? {
        val root = projectRootPath() ?: return null
        return relativize(root, Path.of(file.path))
    }

    private fun relativize(
        root: Path,
        path: Path,
    ): String? =
        try {
            val rel = root.relativize(path).toString().replace('\\', '/')
            rel.takeUnless { it.startsWith("..") || it.isEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }

    /** Creates `.architect-studio/diagrams/` if missing and ensures the root `.gitignore` excludes
     *  it (spec §3/§15). Safe to call repeatedly. */
    fun ensureWorkspace(): Path {
        val root = workspaceRoot() ?: error("project has no base path")
        Files.createDirectories(root.resolve("diagrams"))
        ensureGitignore()
        return root
    }

    private fun ensureGitignore() {
        val projectRoot = projectRootPath() ?: return
        val gitignorePath = projectRoot.resolve(".gitignore")
        val existing = if (Files.exists(gitignorePath)) Files.readString(gitignorePath) else null
        val updated = ensureArchitectStudioGitignoreEntry(existing) ?: return
        if (!Files.exists(gitignorePath)) {
            Files.writeString(gitignorePath, "")
        }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(gitignorePath) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        if (document == null) {
            Files.writeString(gitignorePath, updated)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(gitignorePath)
            return
        }
        WriteCommandAction.runWriteCommandAction(project, "Ignore Architect Studio Working Files", null, {
            document.setText(updated)
        })
        FileDocumentManager.getInstance().saveDocument(document)
    }

    /** True for any file living under `.architect-studio/` — the internal working `.apollon` files
     *  and residuals this plugin manages, which must never surface as "diagrams to open" the way a
     *  native `.apollon` file does (spec §6, e.g. in the diagram tool window). */
    fun isWorkingFile(file: VirtualFile): Boolean {
        val root = workspaceRoot() ?: return false
        val rel = relativize(root, Path.of(file.path)) ?: return false
        return rel == "index.json" || rel.startsWith("diagrams/")
    }

    /** The binding for an already-open working `.apollon` file, or `null` if [file] is not one
     *  Architect Studio manages (e.g. a native, non-PUML-backed `.apollon` diagram). */
    fun bindingForWorkingFile(file: VirtualFile): DiagramBinding? {
        val root = workspaceRoot() ?: return null
        if (!Files.exists(root)) return null
        val relWorking = relativize(root, Path.of(file.path)) ?: return null
        val entry = DiagramMappingRepository(root).findByWorkingFile(relWorking) ?: return null
        return resolveBinding(entry, file)
    }

    private fun resolveBinding(
        entry: DiagramEntry,
        workingFile: VirtualFile,
    ): DiagramBinding? {
        val projectRoot = projectRootPath() ?: return null
        val sourceFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectRoot.resolve(entry.source)) ?: return null
        return DiagramBinding(entry, sourceFile, workingFile)
    }

    /** The binding for a `.puml` source file already tracked in the index, or `null` if it has
     *  never been opened through Architect Studio (or the index/working file is missing). Used to
     *  detect external edits to an already-imported source (spec §13), not to import a new one —
     *  see [openOrImport] for that. */
    fun bindingForSource(sourceFile: VirtualFile): DiagramBinding? {
        val root = workspaceRoot() ?: return null
        if (!Files.exists(root)) return null
        val relSource = relativize(sourceFile) ?: return null
        val entry = DiagramMappingRepository(root).findBySource(relSource) ?: return null
        val workingFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(entry.workingFile)) ?: return null
        return DiagramBinding(entry, sourceFile, workingFile)
    }

    /**
     * The "Architect Studio -> Edit" entry point (spec §3): import [source] into (or reuse) its
     * working `.apollon` file and return the virtual file to open on the canvas. A re-import whose
     * source is unchanged since the last sync is a fast, side-effect-free re-open; otherwise this
     * re-parses and merges into the existing working file so canvas layout survives (plan §A6).
     */
    fun openOrImport(source: VirtualFile): OpenOutcome {
        val relSource = relativize(source) ?: return OpenOutcome.Rejected("is outside the project")
        val text =
            try {
                VfsUtilCore.loadText(source)
            } catch (e: Exception) {
                return OpenOutcome.Failed(e.message ?: "could not read the file")
            }
        val hash = sourceHash(source.contentsToByteArray())

        val root =
            try {
                ensureWorkspace()
            } catch (e: Exception) {
                return OpenOutcome.Failed(e.message ?: "could not create the Architect Studio workspace")
            }
        val repo =
            try {
                DiagramMappingRepository(root)
            } catch (e: UnsupportedIndexVersionException) {
                return OpenOutcome.Failed(e.message ?: "index.json is from a newer version of Architect Studio")
            }
        val existing = repo.findBySource(relSource)
        val existingWorkingPath = existing?.let { root.resolve(it.workingFile) }

        if (existing != null && existing.sourceHash == hash && existingWorkingPath != null && Files.exists(existingWorkingPath)) {
            val workingVf =
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(existingWorkingPath)
                    ?: return OpenOutcome.Failed("the working diagram file is missing")
            return OpenOutcome.Opened(workingVf, 0)
        }

        val parsed = PlantUmlDiagramImporter.parse(text)
        if (parsed is DispatchedImport.Rejected) return OpenOutcome.Rejected(parsed.reason)
        parsed as DispatchedImport.Parsed

        val id = existing?.id ?: UUID.randomUUID().toString()
        val workingFileName = Regex("\\.[^.]+$").replace(source.name, "") + ".apollon"
        val workingRelPath = "diagrams/$id/$workingFileName"
        val residualRelPath = "diagrams/$id/source.residual.json"
        val workingPath = root.resolve(workingRelPath)

        val previousModel: JsonObject? =
            if (existingWorkingPath != null && Files.exists(existingWorkingPath)) {
                runCatching { parseModel(Files.readString(existingWorkingPath)) }.getOrNull()
            } else {
                null
            }

        val mapped = parsed.toApollonModel(previousModel, titleFor(source.name))
        val residual =
            parsed.residual.copy(typeKeywords = mapped.typeKeywords, arrowTokens = mapped.arrowTokens, elementAliases = mapped.elementAliases)

        try {
            writeAtomically(workingPath, writeDocumentText("", mapped.model))
            writeAtomically(root.resolve(residualRelPath), residual.toJson())
        } catch (e: Exception) {
            return OpenOutcome.Failed(e.message ?: "could not write the working diagram file")
        }

        val now = Instant.now().toString()
        repo.upsert(DiagramEntry(id, "puml", relSource, workingRelPath, residualRelPath, hash, now, now))
        repo.save()

        val workingVf =
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workingPath)
                ?: return OpenOutcome.Failed("could not create the working diagram file")
        return OpenOutcome.Opened(workingVf, parsed.unsupportedCount)
    }

    private fun titleFor(fileName: String): String = Regex("\\.[^.]+$").replace(fileName, "")

    /**
     * Save round-trip (spec §7): translate the canvas model back into PlantUML and overwrite the
     * original `.puml` — but only if [binding]'s source hasn't changed externally since it was
     * last synced, and only if the regenerated text actually re-parses back to the same diagram
     * (spec §11/§12 - [RoundTripValidator] is the gate).
     */
    fun syncToSource(
        binding: DiagramBinding,
        modelJsonText: String,
        force: Boolean = false,
    ): SyncOutcome {
        val root = workspaceRoot() ?: return SyncOutcome.Failed("project has no base path")
        val model =
            try {
                parseModel(modelJsonText)
            } catch (e: Exception) {
                return SyncOutcome.Failed(e.message ?: "the working diagram file is not valid")
            }

        val currentSourceBytes = binding.sourceFile.contentsToByteArray()
        val currentHash = sourceHash(currentSourceBytes)
        if (!force && currentHash != binding.entry.sourceHash) {
            return SyncOutcome.Stale(binding)
        }

        val residualPath = root.resolve(binding.entry.residualFile)
        val residual =
            if (Files.exists(residualPath)) {
                runCatching { PumlResidual.fromJson(Files.readString(residualPath)) }.getOrDefault(PumlResidual.empty())
            } else {
                PumlResidual.empty()
            }

        val dispatched = PlantUmlDiagramExporter.render(model, residual)
        val candidate = dispatched.text
        val prunedResidual = dispatched.residual

        RoundTripValidator.validate(candidate, model)?.let { reason ->
            return SyncOutcome.Failed("Architect Studio could not safely rewrite the PlantUML source: $reason")
        }

        val currentSourceText = String(currentSourceBytes, binding.sourceFile.charset)
        var wroteSource = false
        if (candidate != currentSourceText) {
            val document = FileDocumentManager.getInstance().getDocument(binding.sourceFile)
            if (document != null) {
                WriteCommandAction.runWriteCommandAction(project, "Update PlantUML Diagram", null, {
                    document.setText(candidate)
                })
                FileDocumentManager.getInstance().saveDocument(document)
            } else {
                writeAtomically(Path.of(binding.sourceFile.path), candidate)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(binding.sourceFile.path))
            }
            wroteSource = true
        }

        val newHash = sourceHash(candidate.toByteArray(Charsets.UTF_8))
        val now = Instant.now().toString()
        val repo =
            try {
                DiagramMappingRepository(root)
            } catch (e: UnsupportedIndexVersionException) {
                return SyncOutcome.Failed(e.message ?: "index.json is from a newer version of Architect Studio")
            }
        repo.upsert(binding.entry.copy(sourceHash = newHash, sourceSyncedAt = now, updatedAt = now))
        repo.save()
        writeAtomically(residualPath, prunedResidual.toJson())

        return if (wroteSource) SyncOutcome.Saved else SyncOutcome.Unchanged
    }

    companion object {
        fun getInstance(project: Project): ArchitectStudioWorkspace = project.getService(ArchitectStudioWorkspace::class.java)
    }
}
