package io.github.isarg.editordeck.actions

import io.github.isarg.editordeck.EditorDeckBundle
import io.github.isarg.editordeck.maven.MavenPomResolver
import io.github.isarg.editordeck.maven.PomLocation
import io.github.isarg.editordeck.maven.PomResolution
import com.intellij.ide.projectView.impl.nodes.NamedLibraryElement
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ArchiveFileSystem
import com.intellij.psi.PsiElement
import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList

/**
 * Base action for Editor Deck features that must keep working during indexing.
 */
abstract class OpenEditorsAction(text: String) : DumbAwareAction(text) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/**
 * Opens the closest POM for a selected dependency JAR or a class inside that JAR.
 */
class OpenMavenPomAction : OpenEditorsAction(EditorDeckBundle.message("action.open.maven.pom.text")) {
    private val resolver = MavenPomResolver()

    override fun update(e: AnActionEvent) {
        // Hide the action entirely unless the current context can be traced back to a
        // local dependency JAR. This keeps unrelated Project View menus clean.
        e.presentation.isEnabledAndVisible = e.project != null && selectedJarReference(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val result = resolver.resolve(selectedJarReference(e))
        when (result) {
            is PomResolution.Found -> openPom(project, result.location)
            is PomResolution.Multiple -> chooseAndOpen(project, result)
            is PomResolution.Missing -> notifyMissingPom(project, result.message)
        }
    }

    private fun selectedJarReference(e: AnActionEvent): String? =
        selectedJarReferences(e).firstOrNull { resolver.extractJarPath(it) != null }

    private fun selectedJarReferences(e: AnActionEvent): Sequence<String> = sequence {
        // Project View, editor popup, External Libraries, and dependency roots expose
        // different data keys. Collect every plausible object and let the resolver
        // decide which strings are real local JAR paths.
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { yieldAll(it.jarReferences()) }
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.forEach { yieldAll(it.jarReferences()) }
        e.getData(CommonDataKeys.PSI_FILE)?.virtualFile?.let { yieldAll(it.jarReferences()) }
        e.getData(CommonDataKeys.PSI_ELEMENT)?.let { yieldAll(it.jarReferences()) }
        e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY)?.forEach { yieldAll(it.jarReferences()) }
        e.getData(NamedLibraryElement.ARRAY_DATA_KEY)?.forEach { yieldAll(it.jarReferences()) }
        e.getData(PlatformCoreDataKeys.SELECTED_ITEMS)?.forEach { yieldAll(it.jarReferences()) }
    }.distinct()

    private fun Any.jarReferences(): List<String> =
        when (this) {
            is VirtualFile -> jarReferences()
            is PsiElement -> containingFile?.virtualFile?.jarReferences().orEmpty()
            is NamedLibraryElement -> OrderRootType.getAllTypes()
                .flatMap { rootType -> orderEntry.getRootFiles(rootType).flatMap { it.jarReferences() } }
            is AbstractTreeNode<*> -> value?.jarReferences().orEmpty()
            else -> emptyList()
        }

    private fun VirtualFile.jarReferences(): List<String> = buildList {
        add(url)
        add(path)
        // JAR entry files live on ArchiveFileSystem; resolve them back to the local JAR
        // so selecting a class inside a dependency works the same as selecting the JAR.
        val localFile = (fileSystem as? ArchiveFileSystem)?.getLocalByEntry(this@jarReferences)
        if (localFile != null) {
            add(localFile.url)
            add(localFile.path)
        }
    }

    private fun openPom(project: Project, location: PomLocation) {
        val file = when (location) {
            is PomLocation.LocalPath -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(location.path)
            // Opening the POM inside the same JAR URL lets IDEA reuse the existing virtual
            // file identity instead of creating duplicate editor tabs for equivalent paths.
            is PomLocation.JarEntry -> JarFileSystem.getInstance().refreshAndFindFileByPath(resolver.jarEntryPath(location))
        }
        if (file == null) {
            Messages.showWarningDialog(
                project,
                EditorDeckBundle.message("dialog.open.maven.pom.cannot.open", location.label()),
                EditorDeckBundle.message("dialog.open.maven.pom.missing.title"),
            )
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true, true)
    }

    private fun chooseAndOpen(project: Project, result: PomResolution.Multiple) {
        val choices = result.locations.map { PomChoice(it.label(), it) }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setTitle(EditorDeckBundle.message("dialog.open.maven.pom.multiple.message", result.checkedDirectory))
            .setRenderer(object : SimpleListCellRenderer<PomChoice>() {
                override fun customize(
                    list: JList<out PomChoice>,
                    value: PomChoice?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    text = value?.label.orEmpty()
                }
            })
            .setItemChosenCallback { choice -> openPom(project, choice.location) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun notifyMissingPom(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Editor Deck")
            .createNotification(EditorDeckBundle.message("notification.open.maven.pom.title"), message, NotificationType.WARNING)
            .notify(project)
    }
}

private fun PomLocation.label(): String =
    when (this) {
        is PomLocation.LocalPath -> path.fileName.toString()
        is PomLocation.JarEntry -> "${jarPath.fileName}!/$entryPath"
    }

private data class PomChoice(
    val label: String,
    val location: PomLocation,
)
