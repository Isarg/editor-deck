package io.github.isarg.editordeck.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Immutable view of one open editor tab at the moment a snapshot is taken.
 */
data class OpenEditorEntry(
    val file: VirtualFile,
    val url: String,
    val name: String,
    val pathText: String,
    val isActive: Boolean,
    val isPinned: Boolean,
    val isPreview: Boolean,
) {
    companion object {
        // Store a compact, presentation-ready entry so Swing rows do not need to touch
        // project path logic while repainting.
        fun from(
            project: Project,
            file: VirtualFile,
            activeFile: VirtualFile?,
            pinned: Boolean,
            preview: Boolean,
        ): OpenEditorEntry {
            val basePath = project.basePath?.replace('\\', '/')
            val filePath = file.path.replace('\\', '/')
            val relative = if (basePath != null && filePath.startsWith("$basePath/")) {
                filePath.removePrefix("$basePath/")
            } else {
                file.presentableUrl
            }
            return OpenEditorEntry(
                file = file,
                url = file.url,
                name = file.name,
                pathText = relative,
                isActive = file == activeFile,
                isPinned = pinned,
                isPreview = preview,
            )
        }
    }
}

/**
 * Point-in-time editor list consumed by Project-independent UI code.
 */
data class EditorSnapshot(
    val entries: List<OpenEditorEntry>,
    val activeUrl: String?,
)
