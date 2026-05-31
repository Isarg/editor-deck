package io.github.isarg.editordeck.toolWindow

import io.github.isarg.editordeck.EditorDeckBundle
import io.github.isarg.editordeck.editor.OpenEditorEntry
import io.github.isarg.editordeck.editor.OpenEditorsService
import io.github.isarg.editordeck.settings.EditorGroupState
import io.github.isarg.editordeck.settings.MIN_GROUP_BODY_HEIGHT_WEIGHT
import io.github.isarg.editordeck.settings.OpenEditorsSettingsService
import io.github.isarg.editordeck.settings.OpenEditorsStateModel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowContentUiType
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.InplaceButton
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.HierarchyEvent
import java.awt.image.BufferedImage
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Registers the dedicated Editor Deck tool window content for each project.
 */
class EditorDeckToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        EditorDeckToolWindowPanel(project, toolWindow)
    }
}

private class EditorDeckToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : JPanel(BorderLayout()), Disposable {
    private fun message(key: String, vararg params: Any): String =
        EditorDeckBundle.message(key, *params)

    private val service = OpenEditorsService.getInstance(project)
    private val settings = OpenEditorsSettingsService.getInstance(project)
    private val rowsPanel = GroupsPanel()
    private val scrollPane = JBScrollPane(rowsPanel)
    private val rowsByUrl = mutableMapOf<String, EditorRow>()
    // Width has two layers: expanded/manual state is what the plugin wants, while
    // renderExpanded is what the current splitter width can actually show.
    private var autoCollapseEnabled = false
    private var manualWidthCollapsed = true
    private var expanded = false
    private var renderExpanded = false
    private var initialOuterWidthApplied = false
    private var restoreOuterWidth: Int? = null
    private var lastExpandedOuterWidth: Int? = null
    private var pendingRevealUrl: String? = null
    private var highlightedUrl: String? = null
    private var hoveredUrl: String? = null
    private val selectedUrls = linkedSetOf<String>()
    private var hoveredGroupId: String? = null
    // Swing DnD does not provide persistent row highlight state, so the TransferHandler
    // records the current target and asks the affected rows/headers to repaint.
    private var dropTargetGroupId: String? = null
    private var dropTargetFileUrl: String? = null
    private var dropAfterTarget = false
    private var collapsedHoverMenu: JPopupMenu? = null
    private var collapsedHoverMenuKey: String? = null
    private var highlightResetTimer: Timer? = null
    private val groupHeadersById = mutableMapOf<String, GroupHeader>()
    // Horizontal group mode is implemented with native ToolWindow Content tabs so IDEA
    // owns title-area overflow and the optional "group tabs" presentation.
    private val contentByGroupId = linkedMapOf<String, Content>()
    private var contentModeVertical: Boolean? = null
    private var contentGroupIds: List<String> = emptyList()
    private var syncingContents = false
    private val widthToggleButton = HoverFillInplaceButton(widthToggleIconButton(collapsed = true)) {
        toggleManualWidthCollapse()
    }
    private val bottomToolbarPanel = bottomToolbar()
    private val contentListener = object : ContentManagerListener {
        override fun selectionChanged(event: ContentManagerEvent) {
            if (syncingContents || settings.state.verticalGroups) return
            val groupId = event.content.getUserData(GROUP_CONTENT_ID_KEY) ?: return
            if (settings.state.selectedHorizontalGroupId == groupId) return
            // User selected a native Content tab/dropdown item. Persist it and rebuild the
            // shared panel so the content area shows only that group.
            settings.state.selectedHorizontalGroupId = groupId
            rebuild()
        }
    }

    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        Disposer.register(project, this)
        toolWindow.component.putClientProperty(ToolWindowContentUi.HIDE_ID_LABEL, "true")
        toolWindow.setTitleActions(
            listOf(
                SelectActiveEditorAction(),
                CollapseAllGroupsAction(),
            ),
        )
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup(
                MoveCurrentGroupFromTitleAction(),
                VerticalGroupsAction(),
                NewGroupFromTitleAction(),
                PathTooltipAction(),
            ),
        )
        toolWindow.contentManager.addContentManagerListener(contentListener)
        rowsPanel.isOpaque = false
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.viewport.isOpaque = false
        scrollPane.isOpaque = false
        scrollPane.viewport.addChangeListener { syncRenderExpandedWithWidth() }
        add(scrollPane, BorderLayout.CENTER)
        add(bottomToolbarPanel, BorderLayout.SOUTH)
        installRootPopup(rowsPanel)
        installToolWindowListener()
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                syncRenderExpandedWithWidth()
            }
        })
        addHierarchyListener { event ->
            if (
                event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L &&
                isShowing &&
                !initialOuterWidthApplied
            ) {
                initialOuterWidthApplied = true
                SwingUtilities.invokeLater {
                    lastExpandedOuterWidth = toolWindow.component.width.takeIf { it > preferredDeckWidth() }
                    adjustToolWindowWidth(preferredDeckWidth())
                }
            }
        }
        if (!settings.state.verticalGroups) {
            manualWidthCollapsed = false
            expanded = true
            renderExpanded = true
        }
        applyWidth(adjustOuter = false)
        service.addRefreshListener(this) { rebuild() }
        rebuild()
    }

    override fun dispose() {
        highlightResetTimer?.stop()
        hideCollapsedHoverMenu()
        runCatching { toolWindow.contentManager.removeContentManagerListener(contentListener) }
    }

    private fun rebuild() {
        // The same panel instance is reused by every ToolWindow Content in horizontal
        // mode, so rebuild is the single place that decides which rows are visible.
        rowsPanel.removeAll()
        rowsByUrl.clear()
        groupHeadersById.clear()
        dropTargetGroupId = null
        dropTargetFileUrl = null
        dropAfterTarget = false
        val snapshot = service.snapshot()
        val entriesByUrl = snapshot.entries.associateBy { it.url }
        selectedUrls.retainAll(entriesByUrl.keys)
        val groups = settings.model.displayGroups()
        syncToolWindowContents(groups, snapshot.activeUrl)

        if (snapshot.entries.isEmpty()) {
            if (editorDeckShouldShowEmptyEditorsRow(settings.state.verticalGroups, hasOpenEditors = false)) {
                rowsPanel.add(EmptyEditorsRow(renderExpanded))
            }
        } else if (settings.state.verticalGroups) {
            val groupPanels = groups.map { group ->
                val entries = group.fileUrls.mapNotNull { entriesByUrl[it] }
                GroupPanel(group, entries)
            }
            groupPanels.forEachIndexed { index, groupPanel ->
                rowsPanel.add(groupPanel)
                val nextGroupPanel = groupPanels.getOrNull(index + 1)
                if (
                    renderExpanded &&
                    nextGroupPanel != null &&
                    groupPanel.stretchable &&
                    nextGroupPanel.stretchable
                ) {
                    rowsPanel.add(GroupResizeHandle(groupPanel.group.id, nextGroupPanel.group.id))
                }
            }
        } else {
            val group = selectedHorizontalGroup(groups, snapshot.activeUrl)
            val entries = group.fileUrls.mapNotNull { entriesByUrl[it] }
            entries.forEach { entry -> rowsPanel.add(EditorRow(entry, group.id)) }
        }

        rowsPanel.revalidate()
        rowsPanel.repaint()
        revalidate()
        repaint()
        if (pendingRevealUrl != null) {
            SwingUtilities.invokeLater { revealPendingEditor() }
        }
    }

    private fun syncToolWindowContents(groups: List<EditorGroupState>, activeUrl: String?) {
        val vertical = settings.state.verticalGroups
        val groupIds = if (vertical) {
            listOf(VERTICAL_CONTENT_ID)
        } else {
            groups.map { it.id }
        }
        val needsRebuild = contentModeVertical != vertical || contentGroupIds != groupIds
        syncingContents = true
        try {
            // Respect IDEA's own "group tabs" toggle after initial mode setup; otherwise a
            // normal content selection would force the title area back to flat tabs.
            if (editorDeckShouldApplyContentUiType(contentModeVertical, vertical)) {
                toolWindow.setContentUiType(
                    if (vertical) ToolWindowContentUiType.COMBO else ToolWindowContentUiType.TABBED,
                    null,
                )
            }
            if (!vertical) {
                manualWidthCollapsed = false
                expanded = true
                renderExpanded = true
            }
            bottomToolbarPanel.isVisible = editorDeckShouldShowWidthToggle(vertical)
            if (needsRebuild) {
                // Recreate Content objects only when the mode or group ID set changes.
                // Renames are handled below to preserve the user's selected Content.
                toolWindow.contentManager.removeAllContents(false)
                contentByGroupId.clear()
                if (vertical) {
                    val content = createToolWindowContent(VERTICAL_CONTENT_ID, "")
                    toolWindow.contentManager.addContent(content)
                    contentByGroupId[VERTICAL_CONTENT_ID] = content
                } else {
                    groups.forEach { group ->
                        val content = createToolWindowContent(group.id, group.name)
                        toolWindow.contentManager.addContent(content)
                        contentByGroupId[group.id] = content
                    }
                }
                contentModeVertical = vertical
                contentGroupIds = groupIds
            } else {
                groups.forEach { group ->
                    contentByGroupId[group.id]?.let { content ->
                        content.setDisplayName(group.name)
                        content.setTabName(group.name)
                    }
                }
            }

            if (!vertical) {
                selectHorizontalContent(groups, activeUrl)
            }
        } finally {
            syncingContents = false
        }
    }

    private fun createToolWindowContent(groupId: String, title: String): Content =
        toolWindow.contentManager.factory.createContent(this, title, false).apply {
            setTabName(title)
            putUserData(GROUP_CONTENT_ID_KEY, groupId)
            putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
            setPreferredFocusableComponent(scrollPane)
        }

    private fun selectedHorizontalGroup(groups: List<EditorGroupState>, activeUrl: String?): EditorGroupState {
        val selectedId = settings.state.selectedHorizontalGroupId
        val activeGroup = activeUrl?.let { url -> settings.model.groupForFile(url) }
            ?.let { group -> groups.firstOrNull { it.id == group.id } }
        val selectedGroupId = editorDeckSelectedHorizontalGroupId(
            selectedGroupId = selectedId,
            activeGroupId = activeGroup?.id,
            groupIds = groups.map { it.id },
            fallbackGroupId = groups.firstOrNull()?.id ?: OpenEditorsStateModel.UNGROUPED_GROUP_ID,
        )
        val selected = groups.firstOrNull { it.id == selectedGroupId } ?: settings.model.ungroupedGroup()
        // Normalize stale persisted IDs immediately, especially after a group is closed.
        settings.state.selectedHorizontalGroupId = selected.id
        return selected
    }

    private fun selectHorizontalContent(groups: List<EditorGroupState>, activeUrl: String?) {
        val selectedGroup = selectedHorizontalGroup(groups, activeUrl)
        val content = contentByGroupId[selectedGroup.id] ?: return
        if (toolWindow.contentManager.selectedContent != content) {
            toolWindow.contentManager.setSelectedContent(content, false)
        }
    }

    private fun setExpanded(value: Boolean, forceWidth: Boolean = false) {
        if (expanded == value && !forceWidth) return
        expanded = value
        val targetWidth = preferredDeckWidth()
        applyWidth()
        renderExpanded = shouldRenderExpanded(anticipatedWidth = targetWidth)
        if (!expanded) {
            setHoveredUrl(null)
            setHoveredGroupId(null)
            hideCollapsedHoverMenu()
        }
        rebuild()
    }

    private fun applyWidth(adjustOuter: Boolean = true) {
        val width = preferredDeckWidth()
        val constraints = editorDeckSizeConstraints(width, JBUI.scale(COLLAPSED_WIDTH))
        minimumSize = Dimension(constraints.minimumWidth, 0)
        preferredSize = Dimension(constraints.preferredWidth, max(height, 1))
        maximumSize = Dimension(constraints.maximumWidth, Int.MAX_VALUE)
        revalidate()
        if (adjustOuter) {
            adjustToolWindowWidth(width)
        }
    }

    private fun preferredDeckWidth(): Int =
        if (expanded) {
            max(lastExpandedOuterWidth ?: 0, JBUI.scale(EXPANDED_WIDTH))
        } else {
            JBUI.scale(COLLAPSED_WIDTH)
        }

    private fun adjustToolWindowWidth(targetWidth: Int) {
        stretchToolWindowToWidth(toolWindow, targetWidth, rememberExpandedWidth = true)
    }

    private fun stretchToolWindowToWidth(
        targetToolWindow: ToolWindow,
        targetWidth: Int,
        rememberExpandedWidth: Boolean,
    ) {
        val toolWindowEx = targetToolWindow as? ToolWindowEx ?: return
        val actualWidth = toolWindowWidth(targetToolWindow) ?: return
        if (targetWidth < actualWidth) {
            if (rememberExpandedWidth) {
                // ToolWindowEx.stretchWidth changes the whole stripe splitter. Remember
                // the previous width so another same-side Tool Window can be restored.
                restoreOuterWidth = actualWidth
                lastExpandedOuterWidth = actualWidth
            }
        } else if (rememberExpandedWidth && targetWidth >= JBUI.scale(EXPANDED_WIDTH)) {
            lastExpandedOuterWidth = max(actualWidth, targetWidth)
        }
        val delta = targetWidth - actualWidth
        if (abs(delta) <= JBUI.scale(2)) return
        runCatching {
            toolWindowEx.stretchWidth(delta)
        }
    }

    private fun toolWindowWidth(targetToolWindow: ToolWindow): Int? {
        val decoratorWidth = runCatching { (targetToolWindow as? ToolWindowEx)?.decorator?.width }.getOrNull()
        return decoratorWidth?.takeIf { it > 0 }
            ?: targetToolWindow.component.width.takeIf { it > 0 }
    }

    private fun restoreToolWindowWidth(targetToolWindow: ToolWindow = toolWindow) {
        val targetWidth = restoreOuterWidth ?: lastExpandedOuterWidth ?: JBUI.scale(EXPANDED_WIDTH)
        restoreOuterWidth = null
        stretchToolWindowToWidth(targetToolWindow, targetWidth, rememberExpandedWidth = false)
    }

    private fun scheduleRestoreToolWindowWidth(targetToolWindow: ToolWindow) {
        if (
            !editorDeckShouldRestorePeerWidth(autoCollapseEnabled, manualWidthCollapsed) ||
            targetToolWindow.id == toolWindow.id ||
            targetToolWindow.anchor != toolWindow.anchor
        ) {
            return
        }
        val targetWidth = restoreOuterWidth ?: lastExpandedOuterWidth ?: JBUI.scale(EXPANDED_WIDTH)
        restoreOuterWidth = null
        fun restore(attempt: Int) {
            if (!editorDeckShouldRestorePeerWidth(autoCollapseEnabled, manualWidthCollapsed)) {
                return
            }
            stretchToolWindowToWidth(targetToolWindow, targetWidth, rememberExpandedWidth = false)
            if (attempt + 1 < WIDTH_RESTORE_ATTEMPTS) {
                // IDEA may finish activating the next Tool Window after our listener
                // fires. A few invokeLater retries keep peer windows from inheriting the
                // collapsed Editor Deck width.
                SwingUtilities.invokeLater { restore(attempt + 1) }
            }
        }
        SwingUtilities.invokeLater { restore(0) }
    }

    private fun installToolWindowListener() {
        project.messageBus.connect(this)
            .subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changedToolWindow: ToolWindow,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    if (changedToolWindow.id == toolWindow.id) {
                        if (
                            changeType in TOOL_WINDOW_SWITCH_EVENTS &&
                            toolWindowManager.activeToolWindowId == toolWindow.id
                        ) {
                            SwingUtilities.invokeLater { syncExpandedStateWithOuterWidth() }
                        }
                        return
                    }
                    if (changedToolWindow.id == toolWindow.id || changedToolWindow.anchor != toolWindow.anchor) {
                        return
                    }
                    if (
                        editorDeckShouldRestorePeerWidth(autoCollapseEnabled, manualWidthCollapsed) &&
                        changeType in TOOL_WINDOW_SWITCH_EVENTS &&
                        toolWindowManager.activeToolWindowId != toolWindow.id
                    ) {
                        SwingUtilities.invokeLater { scheduleRestoreToolWindowWidth(changedToolWindow) }
                    }
                }

                override fun toolWindowShown(shownToolWindow: ToolWindow) {
                    if (shownToolWindow.id == toolWindow.id) {
                        SwingUtilities.invokeLater { syncDeckStateWhenShown() }
                    } else if (editorDeckShouldRestorePeerWidth(autoCollapseEnabled, manualWidthCollapsed)) {
                        SwingUtilities.invokeLater { scheduleRestoreToolWindowWidth(shownToolWindow) }
                    }
                }
            })
    }

    private fun syncDeckStateWhenShown() {
        if (!settings.state.verticalGroups) {
            manualWidthCollapsed = false
            updateWidthToggleButton()
            if (!expanded) {
                setExpanded(true, forceWidth = true)
            } else {
                syncRenderExpandedWithWidth()
            }
            return
        }
        if (manualWidthCollapsed) {
            setExpanded(false, forceWidth = true)
        } else {
            syncExpandedStateWithOuterWidth()
        }
    }

    private fun syncExpandedStateWithOuterWidth() {
        val actualWidth = toolWindowWidth(toolWindow) ?: return
        if (actualWidth >= JBUI.scale(EXPANDED_RENDER_WIDTH_THRESHOLD)) {
            lastExpandedOuterWidth = max(lastExpandedOuterWidth ?: 0, actualWidth)
            setExpanded(true)
            syncRenderExpandedWithWidth()
        }
    }

    private fun syncRenderExpandedWithWidth() {
        val actualWidth = currentRenderWidth() ?: return
        // Manual splitter drags should affect the visual row layout even when the bottom
        // collapse button was not used.
        val widthState = editorDeckWidthStateAfterResize(
            verticalGroups = settings.state.verticalGroups,
            expanded = expanded,
            manuallyCollapsed = manualWidthCollapsed,
            actualWidth = actualWidth,
            expandedRenderWidthThreshold = JBUI.scale(EXPANDED_RENDER_WIDTH_THRESHOLD),
        )
        val stateChanged = expanded != widthState.expanded ||
            manualWidthCollapsed != widthState.manuallyCollapsed ||
            renderExpanded != widthState.renderExpanded
        if (!stateChanged) return
        val manuallyCollapsedChanged = manualWidthCollapsed != widthState.manuallyCollapsed
        expanded = widthState.expanded
        manualWidthCollapsed = widthState.manuallyCollapsed
        renderExpanded = widthState.renderExpanded
        if (manuallyCollapsedChanged) {
            updateWidthToggleButton()
        }
        if (!renderExpanded) {
            setHoveredUrl(null)
        } else {
            hideCollapsedHoverMenu()
        }
        rebuild()
    }

    private fun shouldRenderExpanded(anticipatedWidth: Int? = null): Boolean {
        val actualWidth = currentRenderWidth() ?: return renderExpanded
        val width = max(actualWidth, anticipatedWidth ?: 0)
        return editorDeckShouldRenderExpanded(
            verticalGroups = settings.state.verticalGroups,
            expanded = expanded,
            actualWidth = width,
            expandedRenderWidthThreshold = JBUI.scale(EXPANDED_RENDER_WIDTH_THRESHOLD),
        )
    }

    private fun currentRenderWidth(): Int? {
        val decoratorWidth = runCatching { (toolWindow as? ToolWindowEx)?.decorator?.width }
            .getOrNull()
            ?: 0
        // The outer decorator is the most reliable source after manual resize; viewport
        // values can lag by one layout pass.
        return editorDeckActualRenderWidth(
            panelWidth = width,
            viewportWidth = scrollPane.viewport.width,
            componentWidth = toolWindow.component.width,
            decoratorWidth = decoratorWidth,
        ).takeIf { it > 0 }
    }

    private fun installRootPopup(component: JComponent) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowRootPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowRootPopup(e)
        })
    }

    private fun maybeShowRootPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        hideCollapsedHoverMenu()
        val menu = if (settings.state.verticalGroups) {
            JPopupMenu().apply {
                add(menuItem(message("menu.new.group")) { createGroup() })
            }
        } else {
            currentHorizontalGroup()?.let { groupPopupMenu(it) } ?: JPopupMenu()
        }
        menu.show(e.component, e.x, e.y)
    }

    private fun bottomToolbar(): JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 2, 4)
            add(widthToggleButton, BorderLayout.EAST)
        }

    private fun toggleManualWidthCollapse() {
        if (manualWidthCollapsed) {
            manualWidthCollapsed = false
            hideCollapsedHoverMenu()
            updateWidthToggleButton()
            restoreToolWindowWidth()
            setExpanded(true, forceWidth = true)
            syncRenderExpandedWithWidth()
        } else {
            rememberCurrentWidthForRestore()
            manualWidthCollapsed = true
            updateWidthToggleButton()
            setExpanded(false, forceWidth = true)
        }
    }

    private fun rememberCurrentWidthForRestore() {
        val actualWidth = toolWindowWidth(toolWindow) ?: return
        if (actualWidth > JBUI.scale(COLLAPSED_WIDTH)) {
            restoreOuterWidth = actualWidth
            if (actualWidth >= JBUI.scale(EXPANDED_RENDER_WIDTH_THRESHOLD)) {
                lastExpandedOuterWidth = max(lastExpandedOuterWidth ?: 0, actualWidth)
            }
        }
    }

    private fun updateWidthToggleButton() {
        widthToggleButton.setIcons(widthToggleIconButton(manualWidthCollapsed))
        widthToggleButton.toolTipText = if (manualWidthCollapsed) {
            message("button.expand.deck")
        } else {
            message("button.collapse.deck")
        }
        widthToggleButton.repaint()
    }

    private fun revealActiveEditor() {
        val activeUrl = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.url ?: return
        val activeGroup = settings.model.groupForFile(activeUrl)
        if (settings.state.verticalGroups) {
            // Reveal should open the group first; scrollRectToVisible cannot reveal rows
            // inside collapsed sections.
            activeGroup
                ?.takeIf { it.collapsed }
                ?.let { settings.model.setGroupCollapsed(it.id, false) }
        } else {
            activeGroup?.let { settings.state.selectedHorizontalGroupId = it.id }
        }
        pendingRevealUrl = activeUrl
        if (settings.state.verticalGroups && manualWidthCollapsed) {
            manualWidthCollapsed = false
            updateWidthToggleButton()
            restoreToolWindowWidth()
        }
        if (!expanded) {
            setExpanded(true, forceWidth = true)
        } else {
            rebuild()
        }
    }

    private fun revealPendingEditor() {
        val url = pendingRevealUrl ?: return
        val row = rowsByUrl[url] ?: return
        pendingRevealUrl = null
        highlightedUrl = url
        row.reveal()
        highlightResetTimer?.stop()
        highlightResetTimer = Timer(REVEAL_HIGHLIGHT_MILLIS) {
            highlightedUrl = null
            rowsByUrl[url]?.updateRow()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun setHoveredUrl(url: String?) {
        if (hoveredUrl == url) return
        val previousUrl = hoveredUrl
        hoveredUrl = url
        previousUrl?.let { rowsByUrl[it]?.updateRow() }
        url?.let { rowsByUrl[it]?.updateRow() }
    }

    private fun setHoveredGroupId(groupId: String?) {
        if (hoveredGroupId == groupId) return
        val previousGroupId = hoveredGroupId
        hoveredGroupId = groupId
        previousGroupId?.let { groupHeadersById[it]?.updateHeaderBackground() }
        groupId?.let { groupHeadersById[it]?.updateHeaderBackground() }
    }

    private fun setSelectedUrls(urls: Collection<String>) {
        // Multi-select is intentionally transient UI state. Persisting it would make
        // restored projects feel surprising and conflicts with native editor selection.
        val previousUrls = selectedUrls.toSet()
        selectedUrls.clear()
        selectedUrls.addAll(urls)
        (previousUrls + selectedUrls).forEach { url -> rowsByUrl[url]?.updateRow() }
    }

    private fun toggleSelectedUrl(url: String) {
        val selection = editorDeckSelectionAfterCtrlClick(selectedUrls, url)
        setSelectedUrls(selection)
    }

    private fun popupActionUrls(clickedUrl: String): List<String> =
        editorDeckPopupActionUrls(selectedUrls, clickedUrl)

    private inner class SelectActiveEditorAction : DumbAwareAction(
        message("action.select.opened.file.text"),
        message("action.select.opened.file.description"),
        AllIcons.General.Locate,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = FileEditorManager.getInstance(project).selectedFiles.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            revealActiveEditor()
        }
    }

    private inner class CollapseAllGroupsAction : DumbAwareAction(
        message("action.collapse.all.text"),
        message("action.collapse.all.description"),
        AllIcons.Actions.Collapseall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !settings.state.ungroupedCollapsed ||
                settings.state.groups.any { !it.collapsed }
        }

        override fun actionPerformed(e: AnActionEvent) {
            settings.model.collapseAllGroups()
            service.refresh()
        }
    }

    private inner class PathTooltipAction : ToggleAction(
        message("action.show.path.tooltips.text"),
        message("action.show.path.tooltips.description"),
        AllIcons.Actions.PreviewDetails,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = settings.state.showPathTooltips

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            settings.state.showPathTooltips = state
            rebuild()
        }
    }

    private inner class NewGroupFromTitleAction : DumbAwareAction(
        message("action.new.group.text"),
        message("action.new.group.description"),
        AllIcons.General.Add,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = editorDeckCanCreateGroupFromTitle(settings.state.verticalGroups)
        }

        override fun actionPerformed(e: AnActionEvent) {
            createGroup()
        }
    }

    private inner class MoveCurrentGroupFromTitleAction : ActionGroup(
        message("action.move.current.group.text"),
        true,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val visible = shouldShowHorizontalCurrentGroupMoveMenu()
            e.presentation.isVisible = visible
            e.presentation.isEnabled = visible
        }

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val currentGroupId = settings.state.selectedHorizontalGroupId
                ?.takeIf { shouldShowHorizontalCurrentGroupMoveMenu(it) }
                ?: return emptyArray()
            return settings.model.displayGroups()
                .filter { it.id != currentGroupId }
                .map { target ->
                    object : DumbAwareAction(message("menu.after.group", target.name)) {
                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                        override fun actionPerformed(e: AnActionEvent) {
                            moveGroupAfter(currentGroupId, target.id)
                        }
                    }
                }
                .toTypedArray()
        }
    }

    private inner class VerticalGroupsAction : ToggleAction(
        message("action.vertical.groups.text"),
        message("action.vertical.groups.description"),
        AllIcons.Actions.GroupBy,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = settings.state.verticalGroups

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            settings.state.verticalGroups = state
            hideCollapsedHoverMenu()
            if (!state) {
                manualWidthCollapsed = false
                updateWidthToggleButton()
                restoreToolWindowWidth()
                setExpanded(true, forceWidth = true)
            } else {
                applyWidth()
                rebuild()
            }
        }
    }

    private inner class GroupPanel(
        val group: EditorGroupState,
        private val entries: List<OpenEditorEntry>,
    ) : JPanel(BorderLayout()) {
        val stretchable: Boolean = !group.collapsed && entries.isNotEmpty()
        val bodyHeightWeight: Int
            get() = settings.model.groupBodyHeightWeight(group.id)

        init {
            isOpaque = false
            add(GroupHeader(group, entries), BorderLayout.NORTH)
            if (!group.collapsed && entries.isNotEmpty()) {
                // Each group owns its own scroll area so expanded sections can keep their
                // headers fixed while the file list overflows.
                val filesPanel = JPanel()
                filesPanel.layout = BoxLayout(filesPanel, BoxLayout.Y_AXIS)
                filesPanel.isOpaque = false
                entries.forEach { entry -> filesPanel.add(EditorRow(entry, group.id)) }
                val groupScroll = JBScrollPane(filesPanel)
                groupScroll.border = JBUI.Borders.empty()
                groupScroll.viewport.isOpaque = false
                groupScroll.isOpaque = false
                groupScroll.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                groupScroll.verticalScrollBarPolicy = editorDeckGroupVerticalScrollBarPolicy(renderExpanded)
                add(groupScroll, BorderLayout.CENTER)
            }
        }

        override fun getPreferredSize(): Dimension =
            Dimension(10, minimumGroupHeight() + max(0, entries.size - 1) * JBUI.scale(ROW_HEIGHT))

        fun minimumGroupHeight(): Int =
            JBUI.scale(GROUP_HEADER_HEIGHT) + if (stretchable) JBUI.scale(ROW_HEIGHT) else 0
    }

    private inner class GroupResizeHandle(
        private val topGroupId: String,
        private val bottomGroupId: String,
    ) : JPanel(BorderLayout()) {
        private var hovered = false
        private var startY = 0
        private var startWeights: List<Int> = emptyList()

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
            preferredSize = Dimension(10, JBUI.scale(GROUP_RESIZE_HANDLE_HEIGHT))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(GROUP_RESIZE_HANDLE_HEIGHT))
            val mouse = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }

                override fun mousePressed(e: MouseEvent) {
                    startY = e.yOnScreen
                    startWeights = listOf(
                        settings.model.groupBodyHeightWeight(topGroupId),
                        settings.model.groupBodyHeightWeight(bottomGroupId),
                    )
                }

                override fun mouseDragged(e: MouseEvent) {
                    val weights = startWeights.takeIf { it.size == 2 } ?: return
                    // Store unscaled weights so persisted values remain stable across UI
                    // scale changes.
                    val delta = JBUI.unscale(e.yOnScreen - startY)
                    val adjusted = editorDeckAdjustedGroupHeightWeights(
                        heightWeights = weights,
                        dividerIndex = 0,
                        delta = delta,
                        minimumWeight = MIN_GROUP_BODY_HEIGHT_WEIGHT,
                    )
                    settings.model.setGroupBodyHeightWeight(topGroupId, adjusted[0])
                    settings.model.setGroupBodyHeightWeight(bottomGroupId, adjusted[1])
                    rowsPanel.revalidate()
                    rowsPanel.repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    startWeights = emptyList()
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (!hovered) return
            g.color = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            val y = height / 2
            g.drawLine(JBUI.scale(20), y, width - JBUI.scale(20), y)
        }
    }

    private inner class GroupHeader(
        private val group: EditorGroupState,
        private val entries: List<OpenEditorEntry>,
    ) : JPanel(BorderLayout()) {
        private var pressPoint: Point? = null

        init {
            isOpaque = true
            groupHeadersById[group.id] = this
            updateHeaderBackground()
            border = JBUI.Borders.empty(1, editorDeckGroupHeaderLeftInset())
            preferredSize = Dimension(10, JBUI.scale(GROUP_HEADER_HEIGHT))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(GROUP_HEADER_HEIGHT))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            putClientProperty(DROP_GROUP_ID, group.id)

            val icon = JBLabel(if (group.collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown)
            icon.border = JBUI.Borders.emptyRight(4)
            add(icon, BorderLayout.WEST)

            if (renderExpanded) {
                val counts = settings.model.groupFileCounts(group)
                val label = JBLabel("${group.name}  ${counts.summaryText()}")
                label.font = JBFont.small()
                label.foreground = UIUtil.getContextHelpForeground()
                add(label, BorderLayout.CENTER)
            }

            transferHandler = EditorDeckTransferHandler()
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    pressPoint = e.point
                    maybeShowGroupPopup(e, group)
                }

                override fun mouseEntered(e: MouseEvent) {
                    setHoveredGroupId(group.id)
                    showCollapsedGroupMenu(this@GroupHeader, group, entries)
                }

                override fun mouseExited(e: MouseEvent) {
                    SwingUtilities.invokeLater {
                        if (!componentContainsPointer(this@GroupHeader)) {
                            setHoveredGroupId(null)
                        }
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    maybeShowGroupPopup(e, group)
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        settings.model.setGroupCollapsed(group.id, !group.collapsed)
                        service.refresh()
                    }
                }

                override fun mouseDragged(e: MouseEvent) {
                    val start = pressPoint ?: return
                    // Avoid stealing ordinary clicks; only start Swing DnD after a small
                    // pointer movement threshold.
                    if (abs(e.x - start.x) + abs(e.y - start.y) > JBUI.scale(4)) {
                        transferHandler?.exportAsDrag(this@GroupHeader, e, TransferHandler.MOVE)
                    }
                }
            }
            installMouseHandler(this, mouse)
        }

        fun updateHeaderBackground() {
            background = when {
                isDropTarget() -> dropTargetBackground()
                hoveredGroupId == group.id -> hoverBackground()
                else -> UIUtil.getPanelBackground()
            }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (!isDropTarget()) return
            g.color = selectedMarkerColor()
            val y = if (dropAfterTarget) height - JBUI.scale(2) else 0
            g.fillRect(0, y, width, JBUI.scale(2))
        }

        private fun isDropTarget(): Boolean =
            dropTargetGroupId == group.id && dropTargetFileUrl == null
    }

    private inner class EditorRow(
        private val entry: OpenEditorEntry,
        private val groupId: String,
    ) : JPanel(BorderLayout()) {
        private var showSelectionMarker = false
        private val closeButton = InplaceButton(
            IconButton(message("button.close"), AllIcons.General.InlineClose, AllIcons.General.InlineCloseHover),
        ) {
            service.close(entry.url)
        }
        private val pinButton = InplaceButton(pinIconButton(entry.isPinned)) {
            service.togglePinned(entry.url)
        }
        private val rowHoverListener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                setHoveredUrl(entry.url)
                showCollapsedEditorMenu(this@EditorRow, entry)
            }

            override fun mouseExited(e: MouseEvent) {
                scheduleHoverRecheck(entry.url)
            }
        }
        private var pressPoint: Point? = null

        init {
            isOpaque = true
            border = if (renderExpanded) {
                JBUI.Borders.empty(1, editorDeckExpandedRowLeftInset(), 1, 4)
            } else {
                JBUI.Borders.empty(1, editorDeckGroupHeaderLeftInset())
            }
            preferredSize = Dimension(
                JBUI.scale(editorDeckRowPreferredWidth(renderExpanded, COLLAPSED_WIDTH)),
                JBUI.scale(ROW_HEIGHT),
            )
            maximumSize = Dimension(
                if (renderExpanded) Int.MAX_VALUE else JBUI.scale(COLLAPSED_WIDTH),
                JBUI.scale(ROW_HEIGHT),
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            transferHandler = EditorDeckTransferHandler()
            putClientProperty(DRAG_PAYLOAD, DragPayload.File(entry.url, groupId))
            putClientProperty(DROP_GROUP_ID, groupId)
            putClientProperty(DROP_FILE_URL, entry.url)
            toolTipText = rowTooltip().takeIf { renderExpanded }
            rowsByUrl[entry.url] = this

            if (renderExpanded) {
                closeButton.toolTipText = message("button.close")
                add(buttonSlot(closeButton), BorderLayout.WEST)
                add(fileTextPanel(), BorderLayout.CENTER)
                pinButton.toolTipText = if (entry.isPinned) message("button.unpin") else message("button.pin")
                add(buttonSlot(pinButton), BorderLayout.EAST)
            } else {
                val icon = JBLabel(entry.file.fileType.icon)
                icon.horizontalAlignment = JBLabel.CENTER
                add(icon, BorderLayout.CENTER)
            }

            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    pressPoint = e.point
                    maybeShowEditorPopup(e, entry, groupId)
                }

                override fun mouseReleased(e: MouseEvent) {
                    maybeShowEditorPopup(e, entry, groupId)
                }

                override fun mouseDragged(e: MouseEvent) {
                    val start = pressPoint ?: return
                    if (abs(e.x - start.x) + abs(e.y - start.y) > JBUI.scale(4)) {
                        transferHandler?.exportAsDrag(this@EditorRow, e, TransferHandler.MOVE)
                    }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    if (e.isControlDown && e.clickCount == 1) {
                        toggleSelectedUrl(entry.url)
                        return
                    }
                    when (e.clickCount) {
                        1 -> {
                            if (selectedUrls.isNotEmpty()) {
                                setSelectedUrls(emptyList())
                            }
                            service.open(entry.url)
                        }
                        2 -> {
                            if (selectedUrls.isNotEmpty()) {
                                setSelectedUrls(emptyList())
                            }
                            service.open(entry.url)
                            if (entry.isPreview) {
                                service.makePermanent(entry.url)
                            }
                        }
                    }
                }
            }
            installMouseHandler(this, mouse)
            installRowHoverTracking(this)
            updateRow()
        }

        private fun fileTextPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.isOpaque = false
            val fileIcon = JBLabel(entry.file.fileType.icon)
            fileIcon.border = JBUI.Borders.emptyRight(6)
            panel.add(fileIcon, BorderLayout.WEST)
            panel.add(FileNameAndPathComponent(entry), BorderLayout.CENTER)
            return panel
        }

        fun updateRow() {
            val hovered = hoveredUrl == entry.url
            val selected = entry.url in selectedUrls
            val dropTarget = dropTargetFileUrl == entry.url
            val visualState = editorDeckRowVisualState(
                highlighted = entry.url == highlightedUrl,
                active = entry.isActive,
                selected = selected,
                hovered = hovered,
            )
            showSelectionMarker = visualState.showSelectionMarker
            background = if (dropTarget) dropTargetBackground() else rowBackground(visualState.backgroundKind)
            closeButton.isVisible = renderExpanded && (entry.isActive || hovered)
            pinButton.isVisible = renderExpanded && (entry.isPinned || hovered)
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (showSelectionMarker) {
                g.color = selectedMarkerColor()
                g.fillRect(0, JBUI.scale(2), JBUI.scale(3), height - JBUI.scale(4))
            }
            if (dropTargetFileUrl == entry.url) {
                g.color = selectedMarkerColor()
                val y = if (dropAfterTarget) height - JBUI.scale(2) else 0
                g.fillRect(0, y, width, JBUI.scale(2))
            }
        }

        private fun buttonSlot(button: JComponent): JComponent =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                preferredSize = Dimension(JBUI.scale(22), JBUI.scale(20))
                minimumSize = preferredSize
                maximumSize = preferredSize
                add(button, BorderLayout.CENTER)
            }

        private fun installRowHoverTracking(component: Component) {
            // Child labels/buttons can consume enter/exit events. Install the row hover
            // listener recursively so only one row owns hover state at a time.
            component.addMouseListener(rowHoverListener)
            if (component is JComponent) {
                if (renderExpanded && component.toolTipText == null) {
                    component.toolTipText = rowTooltip()
                }
            }
            if (component is java.awt.Container) {
                component.components.forEach { installRowHoverTracking(it) }
            }
        }

        private fun scheduleHoverRecheck(url: String) {
            SwingUtilities.invokeLater {
                // Tooltip popups and inline buttons can reorder mouseExited delivery.
                // Re-check the real pointer location instead of trusting the event alone.
                if (hoveredUrl == url) {
                    setHoveredUrl(rowUrlUnderPointer())
                }
            }
        }

        private fun rowTooltip(): String? =
            editorDeckRowTooltip(
                renderExpanded = renderExpanded,
                showPathTooltips = settings.state.showPathTooltips,
                name = entry.name,
                path = entry.pathText,
            )

        fun reveal() {
            scrollRectToVisible(Rectangle(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1)))
            updateRow()
        }

        fun containsScreenPoint(point: Point): Boolean =
            runCatching {
                val origin = locationOnScreen
                Rectangle(origin, size).contains(point)
            }.getOrDefault(false)

    }

    private inner class CollapsedEditorPopupItem(
        private val entry: OpenEditorEntry,
    ) : JPanel(BorderLayout()) {
        init {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(0, 4, 0, 10)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = entry.pathText.takeIf { it.isNotBlank() }

            val closeButton = HoverFillInplaceButton(
                IconButton(message("button.close"), AllIcons.General.InlineClose, AllIcons.General.InlineCloseHover),
            ) {
                hideCollapsedHoverMenu()
                service.close(entry.url)
            }
            closeButton.toolTipText = message("button.close")
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyRight(5)
                    add(closeButton, BorderLayout.CENTER)
                },
                BorderLayout.WEST,
            )

            val textPanel = JPanel(BorderLayout())
            textPanel.isOpaque = false
            textPanel.border = JBUI.Borders.emptyRight(6)
            val fileIcon = JBLabel(entry.file.fileType.icon)
            fileIcon.border = JBUI.Borders.emptyRight(5)
            textPanel.add(fileIcon, BorderLayout.WEST)

            val label = JBLabel(entry.name)
            if (entry.isActive) {
                label.font = label.font.deriveFont(Font.BOLD)
            }
            textPanel.add(label, BorderLayout.CENTER)
            add(textPanel, BorderLayout.CENTER)

            val mouse = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = hoverBackground()
                }

                override fun mouseExited(e: MouseEvent) {
                    background = UIUtil.getPanelBackground()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    hideCollapsedHoverMenu()
                    service.open(entry.url)
                }
            }
            editorDeckMouseTargetComponents(this).forEach { target ->
                target.addMouseListener(mouse)
            }
        }

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return Dimension(size.width, JBUI.scale(ROW_HEIGHT))
        }

        override fun getMaximumSize(): Dimension =
            Dimension(Int.MAX_VALUE, JBUI.scale(ROW_HEIGHT))
    }

    private fun maybeShowGroupPopup(e: MouseEvent, group: EditorGroupState) {
        if (!e.isPopupTrigger) return
        hideCollapsedHoverMenu()
        val menu = groupPopupMenu(group)
        menu.show(e.component, e.x, e.y)
    }

    private fun groupPopupMenu(group: EditorGroupState): JPopupMenu =
        JPopupMenu().apply {
            add(menuItem(message("menu.new.group")) { createGroup() })
            addSeparator()
            add(groupMoveToMenu(group.id))
            if (!group.isUngrouped()) {
                addSeparator()
                add(menuItem(message("group.rename.menu")) { renameGroup(group) })
                add(menuItem(message("group.dissolve.menu")) {
                    settings.model.dissolveGroup(group.id)
                    service.refresh()
                })
            }
            addSeparator()
            add(menuItem(message("group.close.menu")) { closeGroup(group) })
        }

    private fun currentHorizontalGroup(): EditorGroupState? {
        val groups = settings.model.displayGroups()
        val currentId = settings.state.selectedHorizontalGroupId
            ?.takeIf { selectedId -> groups.any { it.id == selectedId } }
            ?: groups.firstOrNull()?.id
        return groups.firstOrNull { it.id == currentId }
    }

    private fun showCollapsedGroupMenu(
        component: JComponent,
        group: EditorGroupState,
        entries: List<OpenEditorEntry>,
    ) {
        if (
            !editorDeckShouldShowCollapsedGroupMenu(
                verticalGroups = settings.state.verticalGroups,
                renderExpanded = renderExpanded,
                groupCollapsed = group.collapsed,
                entryCount = entries.size,
            )
        ) {
            return
        }
        val menu = JPopupMenu()
        configureCollapsedHoverMenu(menu)
        entries.forEach { entry ->
            menu.add(CollapsedEditorPopupItem(entry))
        }
        showCollapsedHoverMenu(component, menu, "group:${group.id}")
    }

    private fun showCollapsedEditorMenu(component: JComponent, entry: OpenEditorEntry) {
        if (renderExpanded) return
        val menu = JPopupMenu()
        configureCollapsedHoverMenu(menu)
        menu.add(CollapsedEditorPopupItem(entry))
        showCollapsedHoverMenu(component, menu, "file:${entry.url}")
    }

    private fun configureCollapsedHoverMenu(menu: JPopupMenu) {
        menu.border = JBUI.Borders.empty()
        menu.isBorderPainted = false
    }

    private fun showCollapsedHoverMenu(component: JComponent, menu: JPopupMenu, key: String) {
        if (collapsedHoverMenuKey == key && collapsedHoverMenu?.isVisible == true) return
        hideCollapsedHoverMenu()
        collapsedHoverMenu = menu
        collapsedHoverMenuKey = key
        val x = editorDeckHoverPopupX(toolWindow.anchor, component.width, menu.preferredSize.width)
        menu.show(component, x, 0)
    }

    private fun hideCollapsedHoverMenu() {
        collapsedHoverMenu?.isVisible = false
        collapsedHoverMenu = null
        collapsedHoverMenuKey = null
    }

    private fun maybeShowEditorPopup(e: MouseEvent, entry: OpenEditorEntry, groupId: String) {
        if (!e.isPopupTrigger) return
        hideCollapsedHoverMenu()
        val actionUrls = popupActionUrls(entry.url)
        if (actionUrls.size == 1 && actionUrls.single() == entry.url && entry.url !in selectedUrls) {
            // Right-clicking outside the current multi-selection should act on the clicked
            // row only, matching common list behavior.
            setSelectedUrls(listOf(entry.url))
        }
        val isMultiSelection = actionUrls.size > 1
        val allPinned = actionUrls.all { settings.model.isPinned(it) }
        val menu = JPopupMenu()
        if (EditorDeckEditorPopupSection.CURRENT_GROUP_MOVE in editorDeckEditorPopupLeadingSections(settings.state.verticalGroups)) {
            menu.add(horizontalCurrentGroupMoveToMenu())
            menu.addSeparator()
        }
        menu.add(menuItem(if (isMultiSelection) message("menu.close.selected") else message("menu.close")) {
            service.closeAll(actionUrls)
            setSelectedUrls(emptyList())
        })
        if (!isMultiSelection) {
            menu.add(menuItem(message("menu.close.others.in.group")) {
                service.closeAll(fileOrder(groupId).filterNot { it == entry.url })
            })
        }
        menu.addSeparator()
        menu.add(menuItem(if (allPinned) message("menu.unpin") else message("menu.pin")) {
            actionUrls.forEach { url -> service.setPinned(url, !allPinned) }
        })
        menu.add(moveToMenu(actionUrls))
        if (!isMultiSelection) {
            menu.addSeparator()
            menu.add(menuItem(message("menu.reveal.in.project")) {
                ProjectView.getInstance(project).select(null, entry.file, true)
            })
            menu.add(menuItem(message("menu.copy.path")) {
                CopyPasteManager.getInstance().setContents(StringSelection(entry.file.presentableUrl))
            })
        }
        menu.show(e.component, e.x, e.y)
    }

    private fun moveToMenu(fileUrls: List<String>): JMenu =
        JMenu(message("menu.move.to")).apply {
            add(menuItem(message("menu.move.to.new.group")) {
                createGroupAndMoveFiles(fileUrls)
            })
            addSeparator()
            settings.model.displayGroups().forEach { group ->
                add(
                    menuItem(group.name) {
                        settings.model.moveFilesToGroup(fileUrls, group.id)
                        service.refresh()
                    }.apply {
                        isEnabled = !groupContainsAll(group, fileUrls)
                    },
                )
            }
        }

    private fun horizontalCurrentGroupMoveToMenu(): JMenu {
        val currentGroupId = settings.state.selectedHorizontalGroupId
        val menu = groupMoveToMenu(currentGroupId.orEmpty(), message("menu.move.current.group.to"))
        menu.isEnabled = shouldShowHorizontalCurrentGroupMoveMenu(currentGroupId)
        return menu
    }

    private fun shouldShowHorizontalCurrentGroupMoveMenu(
        currentGroupId: String? = settings.state.selectedHorizontalGroupId,
    ): Boolean =
        editorDeckShouldShowHorizontalMoveCurrentGroupMenu(
            verticalGroups = settings.state.verticalGroups,
            currentGroupId = currentGroupId,
            groupIds = settings.model.displayGroups().map { it.id },
            ungroupedGroupId = OpenEditorsStateModel.UNGROUPED_GROUP_ID,
        )

    private fun groupMoveToMenu(groupId: String, title: String = message("menu.move.to")): JMenu =
        JMenu(title).apply {
            val targets = settings.model.displayGroups().filter { it.id != groupId }
            if (targets.isEmpty()) {
                add(JMenuItem(message("menu.no.other.groups")).apply { isEnabled = false })
                isEnabled = false
                return@apply
            }
            targets.forEach { target ->
                add(
                    menuItem(message("menu.after.group", target.name)) {
                        moveGroupAfter(groupId, target.id)
                    },
                )
            }
        }

    private fun moveGroupAfter(groupId: String, targetGroupId: String) {
        settings.model.moveGroupAfter(groupId, targetGroupId)
        if (!settings.state.verticalGroups) {
            settings.state.selectedHorizontalGroupId = groupId
        }
        service.refresh()
    }

    private fun groupContainsAll(group: EditorGroupState, fileUrls: List<String>): Boolean =
        fileUrls.all { it in group.fileUrls }

    private fun createGroup() {
        val name = Messages.showInputDialog(
            project,
            message("group.name.prompt"),
            message("group.new.dialog.title"),
            Messages.getQuestionIcon(),
            message("group.default.name"),
            null,
        )?.trim().orEmpty()
        if (name.isBlank()) return
        settings.model.createGroup(name)
        service.refresh()
    }

    private fun createGroupAndMoveFiles(fileUrls: List<String>) {
        val name = Messages.showInputDialog(
            project,
            message("group.name.prompt"),
            message("group.new.dialog.title"),
            Messages.getQuestionIcon(),
            message("group.default.name"),
            null,
        )?.trim().orEmpty()
        if (name.isBlank()) return
        settings.model.createGroupWithFiles(name, fileUrls)
        service.refresh()
    }

    private fun renameGroup(group: EditorGroupState) {
        val name = Messages.showInputDialog(
            project,
            message("group.name.prompt"),
            message("group.rename.dialog.title"),
            Messages.getQuestionIcon(),
            group.name,
            null,
        )?.trim().orEmpty()
        if (name.isBlank()) return
        settings.model.renameGroup(group.id, name)
        service.refresh()
    }

    private fun closeGroup(group: EditorGroupState) {
        val choice = Messages.showOkCancelDialog(
            project,
            message("group.close.confirmation", group.fileUrls.size, group.name),
            message("group.close.dialog.title"),
            message("group.close.ok"),
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        )
        if (choice != Messages.OK) return
        val fileUrls = if (group.isUngrouped()) {
            group.fileUrls.toList()
        } else {
            settings.model.closeGroup(group.id)
        }
        service.closeAll(fileUrls)
    }

    private fun menuItem(text: String, action: () -> Unit): JMenuItem =
        JMenuItem(text).apply {
            addActionListener { action() }
        }

    private fun installMouseHandler(component: Component, mouse: MouseAdapter) {
        // Row-level click/drag/menu behavior should work when the pointer is over labels,
        // icons, or custom text components. Inline action buttons opt out in the helper.
        editorDeckMouseTargetComponents(component).forEach { target ->
            target.addMouseListener(mouse)
            target.addMouseMotionListener(mouse)
        }
    }

    private fun rowUrlUnderPointer(): String? {
        if (!isShowing) return null
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return null
        return rowsByUrl.entries.firstOrNull { (_, row) -> row.containsScreenPoint(pointer) }?.key
    }

    private fun componentContainsPointer(component: Component): Boolean =
        runCatching {
            val pointer = MouseInfo.getPointerInfo()?.location ?: return false
            val origin = component.locationOnScreen
            Rectangle(origin, component.size).contains(pointer)
        }.getOrDefault(false)

    private fun setDropTarget(groupId: String?, fileUrl: String?, dropAfter: Boolean) {
        if (dropTargetGroupId == groupId && dropTargetFileUrl == fileUrl && dropAfterTarget == dropAfter) return
        val oldGroupId = dropTargetGroupId
        val oldFileUrl = dropTargetFileUrl
        dropTargetGroupId = groupId
        dropTargetFileUrl = fileUrl
        dropAfterTarget = dropAfter
        oldGroupId?.let { groupHeadersById[it]?.updateHeaderBackground() }
        groupId?.let { groupHeadersById[it]?.updateHeaderBackground() }
        oldFileUrl?.let { rowsByUrl[it]?.updateRow() }
        fileUrl?.let { rowsByUrl[it]?.updateRow() }
    }

    private fun clearDropTarget() {
        setDropTarget(null, null, false)
    }

    private inner class EmptyEditorsRow(expanded: Boolean) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(8, 8)
            preferredSize = Dimension(10, JBUI.scale(36))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
            val icon = JBLabel(AllIcons.Toolwindows.ToolWindowProject)
            icon.horizontalAlignment = JBLabel.CENTER
            add(icon, BorderLayout.WEST)
            if (expanded) {
                val label = JBLabel(message("empty.open.editors"))
                label.foreground = UIUtil.getContextHelpForeground()
                add(label, BorderLayout.CENTER)
            }
        }
    }

    private inner class EditorDeckTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent): Int = MOVE

        override fun createTransferable(c: JComponent): Transferable? {
            val payload = c.getClientProperty(DRAG_PAYLOAD) as? DragPayload
                ?: (c.getClientProperty(DROP_GROUP_ID) as? String)
                    ?.let { DragPayload.Group(it) }
            if (payload != null) {
                configureDragImage(c)
            }
            return payload?.let { EditorDeckTransferable(it) }
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDataFlavorSupported(EditorDeckTransferable.FLAVOR)) return false
            val target = support.component as? JComponent ?: return false
            val targetGroupId = target.getClientProperty(DROP_GROUP_ID) as? String ?: return false
            val targetFileUrl = target.getClientProperty(DROP_FILE_URL) as? String
            val dropAfter = dropAfterTarget(support, target)
            val payload = runCatching {
                support.transferable.getTransferData(EditorDeckTransferable.FLAVOR) as? DragPayload
            }.getOrNull() ?: return false
            val canImport = when (payload) {
                is DragPayload.File -> true
                // Group drag targets are limited to group headers, never file rows.
                is DragPayload.Group -> editorDeckCanDropGroupOnTarget(
                    movingGroupId = payload.id,
                    targetGroupId = targetGroupId,
                    targetFileUrl = targetFileUrl,
                    ungroupedGroupId = OpenEditorsStateModel.UNGROUPED_GROUP_ID,
                )
            }
            if (canImport) {
                setDropTarget(targetGroupId, targetFileUrl, dropAfter)
            } else {
                clearDropTarget()
            }
            return canImport
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val payload = support.transferable.getTransferData(EditorDeckTransferable.FLAVOR) as? DragPayload
                ?: return false
            val target = support.component as? JComponent ?: return false
            val targetGroupId = target.getClientProperty(DROP_GROUP_ID) as? String ?: return false
            val targetFileUrl = target.getClientProperty(DROP_FILE_URL) as? String
            val dropAfter = dropAfterTarget(support, target)
            val model = settings.model
            when (payload) {
                is DragPayload.File -> {
                    val index = targetFileUrl?.let { fileUrl ->
                        val currentOrder = fileOrder(targetGroupId)
                        editorDeckDropIndex(currentOrder, fileUrl, listOf(payload.url), dropAfter)
                    }
                    model.moveFilesToGroup(listOf(payload.url), targetGroupId, index)
                }
                is DragPayload.Group -> {
                    if (
                        !editorDeckCanDropGroupOnTarget(
                            movingGroupId = payload.id,
                            targetGroupId = targetGroupId,
                            targetFileUrl = targetFileUrl,
                            ungroupedGroupId = OpenEditorsStateModel.UNGROUPED_GROUP_ID,
                        )
                    ) {
                        return false
                    }
                    val currentOrder = model.displayGroups().map { it.id }
                    model.moveDisplayGroups(
                        listOf(payload.id),
                        editorDeckDropIndex(currentOrder, targetGroupId, listOf(payload.id), dropAfter),
                    )
                }
            }
            clearDropTarget()
            service.refresh()
            return true
        }

        override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
            clearDropTarget()
            setDragImage(null)
            super.exportDone(source, data, action)
        }

        private fun configureDragImage(component: JComponent) {
            // Swing's default drag cue is subtle on some LAFs; a translucent snapshot
            // gives feedback similar to IDEA's file-move drag image.
            val width = max(component.width, component.preferredSize.width).coerceAtLeast(1)
            val height = max(component.height, component.preferredSize.height).coerceAtLeast(1)
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.composite = AlphaComposite.SrcOver.derive(editorDeckDragImageOpacity())
                component.paint(graphics)
            } finally {
                graphics.dispose()
            }
            setDragImage(image)
            setDragImageOffset(Point(JBUI.scale(8), JBUI.scale(8)))
        }

        private fun dropAfterTarget(support: TransferSupport, target: JComponent): Boolean =
            editorDeckDropAfterTarget(
                dropY = support.dropLocation.dropPoint?.y ?: 0,
                targetHeight = target.height.takeIf { it > 0 } ?: target.preferredSize.height,
            )
    }

    private fun fileOrder(groupId: String): List<String> =
        if (groupId == OpenEditorsStateModel.UNGROUPED_GROUP_ID) {
            settings.state.rootFileUrls
        } else {
            settings.state.groups.firstOrNull { it.id == groupId }?.fileUrls.orEmpty()
        }

    private fun activeBackground(): Color =
        JBColor.namedColor("EditorDeck.activeBackground", JBColor(0xDCEBFF, 0x2D3F57))

    private fun hoverBackground(): Color =
        JBColor.namedColor("EditorDeck.hoverBackground", JBColor(0xEEF2F7, 0x3A3D41))

    private fun dropTargetBackground(): Color =
        JBColor.namedColor("EditorDeck.dropTargetBackground", JBColor(0xEAF3FF, 0x2B405D))

    private fun selectedBackground(): Color =
        JBColor.namedColor("EditorDeck.selectedBackground", JBColor(0xE7F1FF, 0x26384F))

    private fun selectedMarkerColor(): Color =
        JBColor.namedColor("EditorDeck.selectedMarker", JBColor(0x3574F0, 0x548AF7))

    private fun revealBackground(): Color =
        JBColor.namedColor("EditorDeck.revealBackground", JBColor(0xFFF4CE, 0x514322))

    private fun rowBackground(kind: EditorDeckRowBackgroundKind): Color =
        when (kind) {
            EditorDeckRowBackgroundKind.REVEAL -> revealBackground()
            EditorDeckRowBackgroundKind.ACTIVE -> activeBackground()
            EditorDeckRowBackgroundKind.SELECTED -> selectedBackground()
            EditorDeckRowBackgroundKind.HOVER -> hoverBackground()
            EditorDeckRowBackgroundKind.DEFAULT -> UIUtil.getPanelBackground()
        }

    private fun pinIconButton(pinned: Boolean): IconButton =
        if (pinned) {
            IconButton(message("button.unpin"), AllIcons.General.PinSelected, AllIcons.General.PinSelectedHovered)
        } else {
            IconButton(message("button.pin"), AllIcons.General.Pin_tab, AllIcons.General.PinHovered)
        }

    private fun widthToggleIconButton(collapsed: Boolean): IconButton =
        if (collapsed) {
            IconButton(
                message("button.expand.deck"),
                AllIcons.General.ExpandComponent,
                AllIcons.General.ExpandComponentHover,
            )
        } else {
            IconButton(
                message("button.collapse.deck"),
                AllIcons.General.CollapseComponent,
                AllIcons.General.CollapseComponentHover,
            )
        }

    private inner class GroupsPanel : JPanel(null), Scrollable {
        override fun doLayout() {
            // Vertical groups behave like VS Code sections: headers keep their compact
            // height, and expanded bodies share whatever height is left according to
            // persisted weights.
            val visibleComponents = components.filter { it.isVisible }
            val groupPanels = visibleComponents.filterIsInstance<GroupPanel>()
            val stretchableGroups = groupPanels.filter { it.stretchable }
            val minimumHeight = computeMinimumHeight(visibleComponents)
            val layoutHeight = max(height, minimumHeight)
            val headerHeight = groupPanels.sumOf { JBUI.scale(GROUP_HEADER_HEIGHT) }
            val nonGroupHeight = visibleComponents
                .filterNot { it is GroupPanel }
                .sumOf { it.preferredSize.height }
            val bodyHeightTotal = max(0, layoutHeight - headerHeight - nonGroupHeight)
            val bodyHeights = stretchableGroups.distributedBodyHeights(bodyHeightTotal)

            var y = 0
            visibleComponents.forEach { component ->
                val componentHeight = if (component is GroupPanel) {
                    JBUI.scale(GROUP_HEADER_HEIGHT) + (bodyHeights[component] ?: 0)
                } else {
                    component.preferredSize.height
                }
                component.setBounds(0, y, width, componentHeight)
                y += componentHeight
            }
        }

        override fun getPreferredSize(): Dimension =
            Dimension(super.getPreferredSize().width, computeMinimumHeight(components.filter { it.isVisible }))

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = JBUI.scale(ROW_HEIGHT)

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = max(JBUI.scale(ROW_HEIGHT), visibleRect.height - JBUI.scale(ROW_HEIGHT))

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean {
            val viewportHeight = parent?.height ?: return false
            // Track height only when the whole section stack fits; otherwise the outer
            // scroll pane should scroll between groups.
            return viewportHeight >= computeMinimumHeight(components.filter { it.isVisible })
        }

        private fun computeMinimumHeight(visibleComponents: List<Component>): Int =
            visibleComponents.sumOf { component ->
                when (component) {
                    is GroupPanel -> component.minimumGroupHeight()
                    else -> component.preferredSize.height
                }
            }

        private fun List<GroupPanel>.distributedBodyHeights(bodyHeightTotal: Int): Map<GroupPanel, Int> =
            zip(
                editorDeckDistributedGroupBodyHeights(
                    bodyHeightTotal = bodyHeightTotal,
                    minimumBodyHeight = JBUI.scale(ROW_HEIGHT),
                    heightWeights = map { it.bodyHeightWeight },
                ),
            ).toMap()
    }

    private inner class FileNameAndPathComponent(
        private val entry: OpenEditorEntry,
    ) : JComponent() {
        private val nameFont: Font =
            if (entry.isPreview) JBFont.label().asItalic() else JBFont.label()
        private val pathFont: Font = JBFont.small()

        init {
            isOpaque = false
            toolTipText = rowTooltip()
        }

        override fun getPreferredSize(): Dimension =
            Dimension(10, JBUI.scale(ROW_HEIGHT))

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                val nameMetrics = getFontMetrics(nameFont)
                val pathMetrics = getFontMetrics(pathFont)
                val baseline = (height - nameMetrics.height) / 2 + nameMetrics.ascent
                val pathText = entry.pathText.takeIf { it.isNotBlank() }
                // File name always wins. The weak path text is only drawn when there is
                // enough leftover width, matching Project View's file details behavior.
                val layout = editorDeckTextLayout(
                    availableWidth = width,
                    namePreferredWidth = nameMetrics.stringWidth(entry.name),
                    detailPreferredWidth = pathText?.let { pathMetrics.stringWidth(it) } ?: 0,
                    gap = JBUI.scale(12),
                    minimumDetailWidth = JBUI.scale(MIN_DETAIL_WIDTH),
                )

                g2.font = nameFont
                g2.color = UIUtil.getLabelForeground()
                g2.drawString(trimToFit(entry.name, nameMetrics, layout.nameWidth), 0, baseline)

                if (pathText != null && layout.showDetail) {
                    val path = trimToFit(pathText, pathMetrics, layout.detailWidth)
                    g2.font = pathFont
                    g2.color = detailForeground()
                    g2.drawString(path, width - pathMetrics.stringWidth(path), baseline)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun rowTooltip(): String? =
            editorDeckRowTooltip(
                renderExpanded = renderExpanded,
                showPathTooltips = settings.state.showPathTooltips,
                name = entry.name,
                path = entry.pathText,
            )
    }

    private fun trimToFit(text: String, metrics: FontMetrics, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (metrics.stringWidth(text) <= maxWidth) return text
        val ellipsis = "..."
        val ellipsisWidth = metrics.stringWidth(ellipsis)
        if (ellipsisWidth >= maxWidth) return ""
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }

    private fun detailForeground(): Color =
        JBColor.namedColor("EditorDeck.detailForeground", JBColor(0x8A8F98, 0x6F737A))

    private fun EditorGroupState.isUngrouped(): Boolean =
        id == OpenEditorsStateModel.UNGROUPED_GROUP_ID

    companion object {
        private val COLLAPSED_WIDTH = editorDeckDefaultCollapsedWidth()
        private const val EXPANDED_WIDTH = 360
        private const val EXPANDED_RENDER_WIDTH_THRESHOLD = 160
        private const val ROW_HEIGHT = 24
        private const val GROUP_HEADER_HEIGHT = 22
        private const val GROUP_RESIZE_HANDLE_HEIGHT = 5
        private const val MIN_DETAIL_WIDTH = 48
        private const val WIDTH_RESTORE_ATTEMPTS = 5
        private const val REVEAL_HIGHLIGHT_MILLIS = 1200
        private const val DRAG_PAYLOAD = "editorDeckDragPayload"
        private const val DROP_GROUP_ID = "editorDeckDropGroupId"
        private const val DROP_FILE_URL = "editorDeckDropFileUrl"
        private const val VERTICAL_CONTENT_ID = "editor-deck-vertical-content"
        private val GROUP_CONTENT_ID_KEY = Key.create<String>("editorDeckGroupContentId")
        private val TOOL_WINDOW_SWITCH_EVENTS = setOf(
            ToolWindowManagerEventType.ActivateToolWindow,
            ToolWindowManagerEventType.ShowToolWindow,
        )
    }
}

private sealed class DragPayload {
    data class File(val url: String, val groupId: String) : DragPayload()
    data class Group(val id: String) : DragPayload()
}

data class EditorDeckTextLayout(
    val nameWidth: Int,
    val detailWidth: Int,
    val showDetail: Boolean,
)

data class EditorDeckSizeConstraints(
    val minimumWidth: Int,
    val preferredWidth: Int,
    val maximumWidth: Int,
)

data class EditorDeckWidthState(
    val expanded: Boolean,
    val manuallyCollapsed: Boolean,
    val renderExpanded: Boolean,
)

enum class EditorDeckRowBackgroundKind {
    REVEAL,
    ACTIVE,
    SELECTED,
    HOVER,
    DEFAULT,
}

enum class EditorDeckEditorPopupSection {
    CURRENT_GROUP_MOVE,
    SEPARATOR,
    EDITOR_ACTIONS,
}

data class EditorDeckRowVisualState(
    val backgroundKind: EditorDeckRowBackgroundKind,
    val showSelectionMarker: Boolean,
)

fun editorDeckTextLayout(
    availableWidth: Int,
    namePreferredWidth: Int,
    detailPreferredWidth: Int,
    gap: Int,
    minimumDetailWidth: Int,
): EditorDeckTextLayout {
    if (availableWidth <= 0) {
        return EditorDeckTextLayout(nameWidth = 0, detailWidth = 0, showDetail = false)
    }
    // Do not shrink the filename to make room for details; truncated names are more
    // harmful than hidden paths in a tab list.
    if (detailPreferredWidth <= 0 || namePreferredWidth + gap + minimumDetailWidth > availableWidth) {
        return EditorDeckTextLayout(nameWidth = availableWidth, detailWidth = 0, showDetail = false)
    }
    val detailWidth = min(detailPreferredWidth, availableWidth - namePreferredWidth - gap)
    return EditorDeckTextLayout(
        nameWidth = namePreferredWidth,
        detailWidth = detailWidth,
        showDetail = detailWidth > 0,
    )
}

fun editorDeckShouldRenderExpanded(
    actualWidth: Int,
    expandedRenderWidthThreshold: Int,
): Boolean = actualWidth >= expandedRenderWidthThreshold

fun editorDeckShouldRenderExpanded(
    verticalGroups: Boolean,
    expanded: Boolean,
    actualWidth: Int,
    expandedRenderWidthThreshold: Int,
): Boolean =
    expanded && (!verticalGroups || editorDeckShouldRenderExpanded(actualWidth, expandedRenderWidthThreshold))

fun editorDeckShouldApplyContentUiType(
    previousVerticalMode: Boolean?,
    verticalGroups: Boolean,
): Boolean = previousVerticalMode != verticalGroups

fun editorDeckWidthStateAfterResize(
    verticalGroups: Boolean,
    expanded: Boolean,
    manuallyCollapsed: Boolean,
    actualWidth: Int,
    expandedRenderWidthThreshold: Int,
): EditorDeckWidthState {
    if (!verticalGroups) {
        return EditorDeckWidthState(expanded = true, manuallyCollapsed = false, renderExpanded = true)
    }
    val shouldExpand = actualWidth >= expandedRenderWidthThreshold
    return EditorDeckWidthState(
        expanded = shouldExpand,
        manuallyCollapsed = !shouldExpand,
        renderExpanded = shouldExpand,
    )
}

fun editorDeckRowVisualState(
    highlighted: Boolean,
    active: Boolean,
    selected: Boolean,
    hovered: Boolean,
): EditorDeckRowVisualState =
    EditorDeckRowVisualState(
        backgroundKind = when {
            highlighted -> EditorDeckRowBackgroundKind.REVEAL
            active -> EditorDeckRowBackgroundKind.ACTIVE
            selected -> EditorDeckRowBackgroundKind.SELECTED
            hovered -> EditorDeckRowBackgroundKind.HOVER
            else -> EditorDeckRowBackgroundKind.DEFAULT
        },
        showSelectionMarker = selected,
    )

fun editorDeckSizeConstraints(targetWidth: Int, collapsedWidth: Int): EditorDeckSizeConstraints =
    EditorDeckSizeConstraints(
        minimumWidth = collapsedWidth,
        preferredWidth = targetWidth,
        maximumWidth = Int.MAX_VALUE,
    )

fun editorDeckActualRenderWidth(
    panelWidth: Int,
    viewportWidth: Int,
    componentWidth: Int,
    decoratorWidth: Int,
): Int =
    // Ordered by reliability after Tool Window splitter changes.
    listOf(decoratorWidth, componentWidth, viewportWidth, panelWidth)
        .firstOrNull { it > 0 }
        ?: 0

fun editorDeckShouldRestorePeerWidth(
    autoCollapseEnabled: Boolean,
    manuallyCollapsed: Boolean,
): Boolean = autoCollapseEnabled || manuallyCollapsed

fun editorDeckRowTooltip(
    renderExpanded: Boolean,
    showPathTooltips: Boolean,
    name: String,
    path: String,
): String? =
    if (!renderExpanded) {
        name
    } else {
        path.takeIf { showPathTooltips && it.isNotBlank() }
    }

fun editorDeckShouldShowCollapsedGroupMenu(
    verticalGroups: Boolean,
    renderExpanded: Boolean,
    groupCollapsed: Boolean,
    entryCount: Int,
): Boolean = verticalGroups && !renderExpanded && groupCollapsed && entryCount > 0

fun editorDeckShouldShowWidthToggle(verticalGroups: Boolean): Boolean = verticalGroups

fun editorDeckCanCreateGroupFromTitle(verticalGroups: Boolean): Boolean = true

fun editorDeckCanDropGroupOnTarget(
    movingGroupId: String,
    targetGroupId: String?,
    targetFileUrl: String?,
    ungroupedGroupId: String,
): Boolean =
    targetGroupId != null &&
        targetFileUrl == null &&
        targetGroupId != movingGroupId

fun editorDeckDropAfterTarget(dropY: Int, targetHeight: Int): Boolean =
    targetHeight > 0 && dropY >= targetHeight / 2

fun editorDeckDropIndex(
    currentOrder: List<String>,
    targetId: String,
    movingIds: List<String>,
    dropAfterTarget: Boolean,
): Int {
    val targetIndex = currentOrder.indexOf(targetId)
    if (targetIndex < 0) return currentOrder.size
    // The moving items are removed before insertion, so target indices after those items
    // need to slide left.
    val movingBeforeTarget = currentOrder.take(targetIndex).count { it in movingIds }
    val beforeIndex = targetIndex - movingBeforeTarget
    return beforeIndex + if (dropAfterTarget) 1 else 0
}

fun editorDeckDragImageOpacity(): Float = 0.68f

fun editorDeckShouldShowHorizontalMoveCurrentGroupMenu(
    verticalGroups: Boolean,
    currentGroupId: String?,
    groupIds: List<String>,
    ungroupedGroupId: String,
): Boolean =
    !verticalGroups &&
        currentGroupId != null &&
        currentGroupId in groupIds &&
        groupIds.any { it != currentGroupId }

fun editorDeckShouldShowEmptyEditorsRow(
    verticalGroups: Boolean,
    hasOpenEditors: Boolean,
): Boolean = verticalGroups && !hasOpenEditors

fun editorDeckEditorPopupLeadingSections(verticalGroups: Boolean): List<EditorDeckEditorPopupSection> =
    if (verticalGroups) {
        listOf(EditorDeckEditorPopupSection.EDITOR_ACTIONS)
    } else {
        listOf(
            EditorDeckEditorPopupSection.CURRENT_GROUP_MOVE,
            EditorDeckEditorPopupSection.SEPARATOR,
            EditorDeckEditorPopupSection.EDITOR_ACTIONS,
        )
    }

fun editorDeckDefaultCollapsedWidth(): Int = 32

fun editorDeckSelectionAfterCtrlClick(
    selectedUrls: Set<String>,
    clickedUrl: String,
): LinkedHashSet<String> =
    LinkedHashSet(selectedUrls).apply {
        if (!add(clickedUrl)) {
            remove(clickedUrl)
        }
    }

fun editorDeckPopupActionUrls(
    selectedUrls: Set<String>,
    clickedUrl: String,
): List<String> =
    if (clickedUrl in selectedUrls && selectedUrls.isNotEmpty()) {
        selectedUrls.toList()
    } else {
        listOf(clickedUrl)
    }

fun editorDeckSelectedHorizontalGroupId(
    selectedGroupId: String?,
    activeGroupId: String?,
    groupIds: List<String>,
    fallbackGroupId: String,
): String =
    when {
        selectedGroupId != null && selectedGroupId in groupIds -> selectedGroupId
        activeGroupId != null && activeGroupId in groupIds -> activeGroupId
        fallbackGroupId in groupIds -> fallbackGroupId
        else -> groupIds.firstOrNull() ?: fallbackGroupId
    }

fun editorDeckVisibleHorizontalGroupCount(
    availableWidth: Int,
    tabWidths: List<Int>,
    overflowButtonWidth: Int,
): Int {
    if (availableWidth <= 0 || tabWidths.isEmpty()) return 0
    val total = tabWidths.sum()
    if (total <= availableWidth) return tabWidths.size
    var used = 0
    var visible = 0
    for (width in tabWidths) {
        if (used + width + overflowButtonWidth > availableWidth) break
        used += width
        visible++
    }
    return visible.coerceAtLeast(1)
}

fun editorDeckHoverPopupX(
    anchor: ToolWindowAnchor,
    componentWidth: Int,
    popupWidth: Int,
): Int =
    if (anchor == ToolWindowAnchor.RIGHT) {
        -popupWidth
    } else {
        componentWidth
    }

fun editorDeckGroupVerticalScrollBarPolicy(renderExpanded: Boolean): Int =
    if (renderExpanded) {
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    } else {
        ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
    }

fun editorDeckRowPreferredWidth(renderExpanded: Boolean, collapsedWidth: Int): Int =
    if (renderExpanded) 10 else collapsedWidth

fun editorDeckGroupHeaderLeftInset(): Int = 6

fun editorDeckExpandedRowLeftInset(): Int = editorDeckGroupHeaderLeftInset()

fun editorDeckDistributedGroupBodyHeights(
    bodyHeightTotal: Int,
    minimumBodyHeight: Int,
    heightWeights: List<Int>,
): List<Int> {
    if (heightWeights.isEmpty()) return emptyList()
    if (bodyHeightTotal <= 0) return List(heightWeights.size) { 0 }
    val minimum = minimumBodyHeight.coerceAtLeast(0)
    val minimumTotal = minimum * heightWeights.size
    if (bodyHeightTotal <= minimumTotal) {
        val base = bodyHeightTotal / heightWeights.size
        var extra = bodyHeightTotal % heightWeights.size
        return List(heightWeights.size) { base + if (extra-- > 0) 1 else 0 }
    }

    val remaining = bodyHeightTotal - minimumTotal
    val weights = heightWeights.map { it.coerceAtLeast(1).toLong() }
    val weightTotal = weights.sum().coerceAtLeast(1L)
    val extras = weights.map { weight -> (remaining.toLong() * weight / weightTotal).toInt() }.toMutableList()
    var extraPixels = remaining - extras.sum()
    // Integer division leaves a few pixels undistributed; hand them to the largest
    // remainders for stable, deterministic layout.
    val remainderOrder = weights.indices.sortedWith(
        compareByDescending<Int> { index -> remaining.toLong() * weights[index] % weightTotal }
            .thenBy { index -> index },
    )
    for (index in remainderOrder) {
        if (extraPixels <= 0) break
        extras[index] += 1
        extraPixels--
    }
    return extras.map { minimum + it }
}

fun editorDeckAdjustedGroupHeightWeights(
    heightWeights: List<Int>,
    dividerIndex: Int,
    delta: Int,
    minimumWeight: Int,
): List<Int> {
    if (dividerIndex !in 0 until heightWeights.lastIndex) {
        return heightWeights.map { it.coerceAtLeast(minimumWeight) }
    }
    val minimum = minimumWeight.coerceAtLeast(1)
    val result = heightWeights.map { it.coerceAtLeast(minimum) }.toMutableList()
    val top = result[dividerIndex]
    val bottom = result[dividerIndex + 1]
    val appliedDelta = delta.coerceIn(minimum - top, bottom - minimum)
    result[dividerIndex] = top + appliedDelta
    result[dividerIndex + 1] = bottom - appliedDelta
    return result
}

fun editorDeckMouseTargetComponents(root: Component): List<Component> {
    val result = mutableListOf<Component>()
    fun collect(component: Component) {
        // Inline action buttons keep their own click behavior.
        if (component is InplaceButton) return
        result.add(component)
        if (component is java.awt.Container) {
            component.components.forEach { child -> collect(child) }
        }
    }
    collect(root)
    return result
}

private class HoverFillInplaceButton(
    source: IconButton,
    listener: java.awt.event.ActionListener,
) : InplaceButton(source, listener) {
    override fun paintHover(g: Graphics) {
        paintHover(g, JBUI.CurrentTheme.ActionButton.hoverBackground())
    }
}

private class EditorDeckTransferable(private val payload: DragPayload) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(FLAVOR)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == FLAVOR

    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) {
            throw UnsupportedOperationException("Unsupported data flavor: $flavor")
        }
        return payload
    }

    companion object {
        val FLAVOR: DataFlavor = DataFlavor(DragPayload::class.java, "Editor Deck item")
    }
}
