package de.tum.cit.aet.apollon.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import de.tum.cit.aet.apollon.puml.isPlantUmlExtension

/** The "Architect Studio" submenu in the Project View's right-click menu (spec §2): visible only
 *  when the selected item is a single file this plugin can import as PlantUML. */
class ArchitectStudioGroup : DefaultActionGroup() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory && isPlantUmlExtension(file.extension)
    }
}
