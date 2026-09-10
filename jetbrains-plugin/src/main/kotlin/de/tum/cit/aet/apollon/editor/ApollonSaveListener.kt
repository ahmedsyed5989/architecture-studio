package de.tum.cit.aet.apollon.editor

import com.intellij.notification.NotificationAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import de.tum.cit.aet.apollon.notify.ArchitectStudioNotifications
import de.tum.cit.aet.apollon.protocol.AutoExport
import de.tum.cit.aet.apollon.protocol.ExportFormat
import de.tum.cit.aet.apollon.settings.ApollonSettings
import de.tum.cit.aet.apollon.workspace.ArchitectStudioWorkspace
import de.tum.cit.aet.apollon.workspace.DiagramBinding
import de.tum.cit.aet.apollon.workspace.SyncOutcome

/**
 * Flushes a pending canvas edit before a save persists the document — the
 * Kotlin counterpart of the VS Code extension's `onWillSaveTextDocument`
 * hook — and, immediately after, writes the auto-export sibling image if one
 * is configured (the extension's separate `onDidSaveTextDocument` hook: there
 * is no distinct "after save" signal as convenient as this one, and the
 * document's on-disk write happens synchronously right after this callback
 * returns, so a few milliseconds early is close enough).
 *
 * `FileDocumentManagerListener` only exists at application scope (see its
 * `TOPIC`), so this walks every open project's editors for the file being
 * saved rather than being registered per-project.
 */
class ApollonSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        for (project in ProjectManager.getInstance().openProjects) {
            for (editor in FileEditorManager.getInstance(project).getEditors(file)) {
                if (editor !is ApollonFileEditor) {
                    continue
                }
                editor.flushForSave()
                syncPumlSource(project, editor)
                runAutoExport(project, editor)
            }
        }
    }

    /** Spec §7: a save on a PUML-backed diagram must also rewrite the original `.puml`. Runs after
     *  [ApollonFileEditor.flushForSave] so the working document already holds the latest canvas
     *  model. No-op for a native (non-PUML-backed) `.apollon` diagram. */
    private fun syncPumlSource(
        project: Project,
        editor: ApollonFileEditor,
    ) {
        if (!editor.file.isInLocalFileSystem) {
            return
        }
        val workspace = ArchitectStudioWorkspace.getInstance(project)
        val binding = workspace.bindingForWorkingFile(editor.file) ?: return
        val modelText = FileDocumentManager.getInstance().getDocument(editor.file)?.text ?: return
        when (val outcome = workspace.syncToSource(binding, modelText)) {
            is SyncOutcome.Saved, is SyncOutcome.Unchanged -> {}
            is SyncOutcome.Stale ->
                ArchitectStudioNotifications.warn(
                    project,
                    "PlantUML source changed outside Architect Studio",
                    "\"${binding.entry.source}\" was modified externally since it was last opened. " +
                        "Saving again would overwrite that change.",
                    NotificationAction.createSimple("Overwrite with canvas changes") {
                        forceSyncPumlSource(project, editor, outcome.binding)
                    },
                )
            is SyncOutcome.Failed ->
                ArchitectStudioNotifications.error(project, "Could not update PlantUML source", outcome.reason)
        }
    }

    private fun forceSyncPumlSource(
        project: Project,
        editor: ApollonFileEditor,
        binding: DiagramBinding,
    ) {
        val modelText = FileDocumentManager.getInstance().getDocument(editor.file)?.text ?: return
        val workspace = ArchitectStudioWorkspace.getInstance(project)
        when (val outcome = workspace.syncToSource(binding, modelText, force = true)) {
            is SyncOutcome.Saved, is SyncOutcome.Unchanged -> {}
            is SyncOutcome.Stale -> {} // cannot happen with force = true
            is SyncOutcome.Failed ->
                ArchitectStudioNotifications.error(project, "Could not update PlantUML source", outcome.reason)
        }
    }

    private fun runAutoExport(
        project: Project,
        editor: ApollonFileEditor,
    ) {
        // An untitled or otherwise non-local file has no sibling to write next to.
        if (!editor.file.isInLocalFileSystem) {
            return
        }
        when (ApollonSettings.getInstance(project).autoExport) {
            AutoExport.off -> {}
            AutoExport.svg -> editor.export(ExportFormat.svg, silent = true)
            AutoExport.png -> editor.export(ExportFormat.png, silent = true)
        }
    }
}
