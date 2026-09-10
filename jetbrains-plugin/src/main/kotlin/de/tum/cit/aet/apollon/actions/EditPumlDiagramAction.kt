package de.tum.cit.aet.apollon.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import de.tum.cit.aet.apollon.notify.ArchitectStudioNotifications
import de.tum.cit.aet.apollon.puml.isPlantUmlExtension
import de.tum.cit.aet.apollon.workspace.ArchitectStudioWorkspace
import de.tum.cit.aet.apollon.workspace.OpenOutcome

/**
 * "Architect Studio > Edit" (spec §2/§3): import (or reopen) the right-clicked `.puml` file's
 * working `.apollon` representation and open it on the canvas exactly like a native diagram. The
 * user never sees "import", "convert" or the working file's own name (spec §19) — only "Edit" and,
 * later, "Save".
 */
class EditPumlDiagramAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory && isPlantUmlExtension(file.extension)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        when (val outcome = ArchitectStudioWorkspace.getInstance(project).openOrImport(file)) {
            is OpenOutcome.Opened -> {
                FileEditorManager.getInstance(project).openFile(outcome.workingFile, true)
                if (outcome.unsupportedCount > 0) {
                    ArchitectStudioNotifications.warn(
                        project,
                        "Some PlantUML was kept but not shown on the canvas",
                        "\"${file.name}\" has ${outcome.unsupportedCount} line(s) Architect Studio can't display. " +
                            "They're preserved as-is and will still be there after you save.",
                    )
                }
            }
            is OpenOutcome.Rejected ->
                ArchitectStudioNotifications.warn(project, "Architect Studio can't edit this file", "\"${file.name}\" ${outcome.reason}.")
            is OpenOutcome.Failed ->
                ArchitectStudioNotifications.error(project, "Could not open \"${file.name}\"", outcome.reason)
        }
    }
}
