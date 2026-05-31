package io.github.isarg.editordeck.editor

import io.github.isarg.editordeck.settings.OpenEditorsSettingsService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-level bridge between IDEA's editor model and Editor Deck's persisted state.
 *
 * It deliberately exposes snapshots instead of Swing components so Project View,
 * actions, and the Tool Window can refresh without sharing UI ownership.
 */
@Service(Service.Level.PROJECT)
class OpenEditorsService(private val project: Project) : Disposable {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    // IDEA reuses the same preview editor tab for different files. Tracking the previous
    // preview URL lets us keep the user's chosen group when that tab is reused.
    private var lastPreviewUrl: String? = null

    private val editorManager: FileEditorManager
        get() = FileEditorManager.getInstance(project)

    private val editorManagerEx: FileEditorManagerEx
        get() = FileEditorManagerEx.getInstanceEx(project)

    init {
        project.messageBus.connect(this)
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refresh()
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) = refresh()
                override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
            })
        refresh()
    }

    fun snapshot(): EditorSnapshot {
        val application = ApplicationManager.getApplication()
        // Project View and tree visitors can ask for data from background threads. Editor
        // window pin/preview APIs are EDT-only, so background snapshots intentionally use
        // the persisted state as a safe fallback.
        val canReadEditorWindowState = application.isDispatchThread
        val active = editorManager.selectedFiles.firstOrNull()
        val pinnedByUrl = if (canReadEditorWindowState) pinnedUrlsFromEditorWindows() else emptyMap()
        val previewByUrl = if (openEditorsShouldReadPreviewState(canReadEditorWindowState)) {
            previewUrlsFromEditorWindows()
        } else {
            emptySet()
        }
        val entries = editorManager.openFiles
            .filter { it.isValid }
            .map { file ->
                OpenEditorEntry.from(
                    project,
                    file,
                    active,
                    pinnedByUrl[file.url] ?: OpenEditorsSettingsService.getInstance(project).model.isPinned(file.url),
                    previewByUrl.contains(file.url),
                )
            }
        val model = OpenEditorsSettingsService.getInstance(project).model
        if (canReadEditorWindowState) {
            preservePreviewTabGroup(entries.filter { it.isPreview }.map { it.url })
        }
        // Reconcile on every snapshot because file open/close events can arrive in bursts
        // and the Tool Window rebuild path is already the single consumer of this state.
        model.reconcileOpenFiles(entries.map { it.url })
        entries.forEach { model.setPinned(it.url, it.isPinned) }
        return EditorSnapshot(entries, active?.url)
    }

    fun findFile(url: String): VirtualFile? =
        VirtualFileManager.getInstance().findFileByUrl(url)

    fun open(url: String) {
        val file = findFile(url) ?: return
        editorManager.openFile(file, true, true)
    }

    fun close(url: String) {
        val file = findFile(url) ?: return
        editorManager.closeFile(file)
        refresh()
    }

    fun closeAll(urls: Collection<String>) {
        urls.forEach { close(it) }
        refresh()
    }

    fun addRefreshListener(parentDisposable: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parentDisposable) {
            listeners.remove(listener)
        }
    }

    fun togglePinned(url: String) {
        val file = findFile(url) ?: return
        setPinned(file, !isPinned(file))
    }

    fun setPinned(url: String, pinned: Boolean) {
        val file = findFile(url) ?: return
        setPinned(file, pinned)
    }

    fun makePermanent(url: String) {
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            // EditorComposite.isPreview mutates editor UI state and must run on EDT.
            application.invokeLater {
                if (!project.isDisposed) {
                    makePermanent(url)
                }
            }
            return
        }
        val file = findFile(url) ?: return
        editorManagerEx.activeSplittersComposites
            .filter { composite -> composite.allEditors.any { it.file == file } && composite.isPreview }
            .forEach { composite ->
                composite.isPreview = false
            }
        refresh()
    }

    fun refresh() {
        // Coalesce UI rebuilds onto EDT. Callers may be action handlers, editor events,
        // or state-model updates, so the service owns the threading boundary.
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                listeners.forEach { it.invoke() }
            }
        }
    }

    override fun dispose() = Unit

    private fun preservePreviewTabGroup(previewUrls: List<String>) {
        val previous = lastPreviewUrl
        val current = previewUrls.firstOrNull()
        if (previous != null && current != null && previous != current) {
            val model = OpenEditorsSettingsService.getInstance(project).model
            if (model.groupForFile(previous) != null) {
                // Replace in-place instead of adding the new preview URL to Ungrouped.
                model.replaceOpenFile(previous, current)
            }
        }
        lastPreviewUrl = current
    }

    private fun setPinned(file: VirtualFile, pinned: Boolean) {
        editorManagerEx.windows
            .filter { it.isFileOpen(file) }
            .forEach { window ->
                runCatching { window.setFilePinned(file, pinned) }
            }
        OpenEditorsSettingsService.getInstance(project).model.setPinned(file.url, pinned)
        refresh()
    }

    private fun isPinned(file: VirtualFile): Boolean =
        editorManagerEx.windows.any { window ->
            window.isFileOpen(file) && runCatching { window.isFilePinned(file) }.getOrDefault(false)
        } || OpenEditorsSettingsService.getInstance(project).model.isPinned(file.url)

    private fun pinnedUrlsFromEditorWindows(): Map<String, Boolean> =
        editorManagerEx.windows
            .flatMap { window ->
                window.fileList.map { file ->
                    file.url to runCatching { window.isFilePinned(file) }.getOrDefault(false)
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.any { it } }

    private fun previewUrlsFromEditorWindows(): Set<String> =
        editorManagerEx.activeSplittersComposites
            .filter { it.isPreview }
            .flatMap { composite -> composite.allEditors.mapNotNull { it.file?.url } }
            .toSet()

    companion object {
        fun getInstance(project: Project): OpenEditorsService =
            project.getService(OpenEditorsService::class.java)
    }
}

fun openEditorsShouldReadPreviewState(isDispatchThread: Boolean): Boolean = isDispatchThread
