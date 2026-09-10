package de.tum.cit.aet.apollon.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import de.tum.cit.aet.apollon.notify.ArchitectStudioNotifications
import de.tum.cit.aet.apollon.puml.isPlantUmlExtension
import de.tum.cit.aet.apollon.workspace.ArchitectStudioWorkspace
import de.tum.cit.aet.apollon.workspace.sourceHash

/**
 * Proactively warns when an already-imported `.puml` file changes on disk while its Architect
 * Studio canvas is open (spec §13). Save-time already refuses to overwrite a stale source
 * ([ArchitectStudioWorkspace.syncToSource]'s `Stale` outcome) — this just surfaces the conflict
 * sooner, before the user loses canvas edits to a surprise. No merge engine: the user decides
 * whether to keep editing (and overwrite on save) or close without saving and reopen to pick up
 * the external change, exactly as spec §13 asks for.
 */
class PumlSourceWatcher(private val project: Project) : BulkFileListener {
    private val alreadyWarnedFor = mutableSetOf<String>()

    override fun after(events: MutableList<out VFileEvent>) {
        val workspace = ArchitectStudioWorkspace.getInstance(project)
        events.filterIsInstance<VFileContentChangeEvent>().forEach { event ->
            val file = event.file
            if (!isPlantUmlExtension(file.extension)) return@forEach
            val relSource = workspace.relativize(file) ?: return@forEach
            val binding = workspace.bindingForSource(file) ?: return@forEach
            if (FileEditorManager.getInstance(project).getEditors(binding.workingFile).isEmpty()) {
                alreadyWarnedFor.remove(relSource)
                return@forEach
            }
            if (sourceHash(file.contentsToByteArray()) == binding.entry.sourceHash) {
                alreadyWarnedFor.remove(relSource)
                return@forEach
            }
            if (!alreadyWarnedFor.add(relSource)) return@forEach
            ArchitectStudioNotifications.warn(
                project,
                "PlantUML source changed outside Architect Studio",
                "\"${file.name}\" was modified on disk while its Architect Studio canvas is open. " +
                    "Saving the canvas will overwrite that external change unless you close without saving and reopen it first.",
            )
        }
    }
}
