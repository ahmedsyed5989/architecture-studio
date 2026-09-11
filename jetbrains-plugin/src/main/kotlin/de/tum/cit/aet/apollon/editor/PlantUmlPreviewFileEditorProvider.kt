package de.tum.cit.aet.apollon.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.tum.cit.aet.apollon.puml.isPlantUmlExtension

/** Registered in `plugin.xml` under `com.intellij.fileEditorProvider`. */
class PlantUmlPreviewFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = !file.isDirectory && isPlantUmlExtension(file.extension)

    override fun createEditor(
        project: Project,
        file: VirtualFile,
    ): FileEditor = PlantUmlPreviewFileEditor(project, file)

    override fun getEditorTypeId(): String = "architect-studio-puml-preview"

    // Alongside the plain-text source tab, after it: Source stays the default
    // for a `.puml` file (it's plain text most editors already know how to
    // handle), Preview is the added capability.
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
