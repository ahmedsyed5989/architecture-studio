package de.tum.cit.aet.apollon.editor

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.tum.cit.aet.apollon.workspace.ArchitectStudioWorkspace

/**
 * A PUML-backed diagram's tab shows the original `.puml` file's name, never the internal working
 * `.apollon` file's own name (spec §6/§19: the generated file must never surface as something the
 * user manages). Returns `null` for everything else — including native `.apollon` diagrams —
 * which keeps the platform's own default tab title.
 */
class ArchitectStudioTabTitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(
        project: Project,
        file: VirtualFile,
    ): String? {
        val binding = ArchitectStudioWorkspace.getInstance(project).bindingForWorkingFile(file) ?: return null
        return binding.sourceFile.name
    }
}
