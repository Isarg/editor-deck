package io.github.isarg.editordeck.toolWindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.ui.InplaceButton
import javax.swing.JLabel
import javax.swing.JPanel

class EditorDeckTextLayoutTest {
    @Test
    fun `detail text is hidden when file name needs the available width`() {
        val layout = editorDeckTextLayout(
            availableWidth = 120,
            namePreferredWidth = 180,
            detailPreferredWidth = 80,
            gap = 12,
            minimumDetailWidth = 48,
        )

        assertEquals(120, layout.nameWidth)
        assertEquals(0, layout.detailWidth)
        assertFalse(layout.showDetail)
    }

    @Test
    fun `detail text is shown only after the full file name fits`() {
        val layout = editorDeckTextLayout(
            availableWidth = 180,
            namePreferredWidth = 80,
            detailPreferredWidth = 140,
            gap = 12,
            minimumDetailWidth = 48,
        )

        assertEquals(80, layout.nameWidth)
        assertEquals(88, layout.detailWidth)
        assertTrue(layout.showDetail)
    }

    @Test
    fun `detail text is hidden when remaining space is too small`() {
        val layout = editorDeckTextLayout(
            availableWidth = 132,
            namePreferredWidth = 80,
            detailPreferredWidth = 140,
            gap = 12,
            minimumDetailWidth = 48,
        )

        assertEquals(132, layout.nameWidth)
        assertEquals(0, layout.detailWidth)
        assertFalse(layout.showDetail)
    }

    @Test
    fun `deck renders expanded only when actual width reaches threshold`() {
        assertFalse(editorDeckShouldRenderExpanded(actualWidth = 219, expandedRenderWidthThreshold = 220))
        assertTrue(editorDeckShouldRenderExpanded(actualWidth = 220, expandedRenderWidthThreshold = 220))
    }

    @Test
    fun `peer tool window width is restored for auto or manual collapsed deck`() {
        assertTrue(editorDeckShouldRestorePeerWidth(autoCollapseEnabled = true, manuallyCollapsed = false))
        assertTrue(editorDeckShouldRestorePeerWidth(autoCollapseEnabled = false, manuallyCollapsed = true))
        assertFalse(editorDeckShouldRestorePeerWidth(autoCollapseEnabled = false, manuallyCollapsed = false))
    }

    @Test
    fun `collapsed deck row tooltip uses file name instead of path`() {
        assertEquals(
            "Service.kt",
            editorDeckRowTooltip(
                renderExpanded = false,
                showPathTooltips = true,
                name = "Service.kt",
                path = "src/main/kotlin/demo/Service.kt",
            ),
        )
    }

    @Test
    fun `expanded deck row tooltip still follows path tooltip setting`() {
        assertEquals(
            "src/main/kotlin/demo/Service.kt",
            editorDeckRowTooltip(
                renderExpanded = true,
                showPathTooltips = true,
                name = "Service.kt",
                path = "src/main/kotlin/demo/Service.kt",
            ),
        )
        assertEquals(
            null,
            editorDeckRowTooltip(
                renderExpanded = true,
                showPathTooltips = false,
                name = "Service.kt",
                path = "src/main/kotlin/demo/Service.kt",
            ),
        )
    }

    @Test
    fun `row mouse targets include nested child components`() {
        val root = JPanel()
        val child = JLabel("Service.kt")
        val nested = JPanel().apply {
            add(child)
        }
        root.add(nested)

        val targets = editorDeckMouseTargetComponents(root)

        assertEquals(listOf(root, nested, child), targets)
    }

    @Test
    fun `row mouse targets skip inline action buttons`() {
        val root = JPanel()
        val button = InplaceButton(IconButton("Close", null, null)) {}
        root.add(button)

        val targets = editorDeckMouseTargetComponents(root)

        assertEquals(listOf(root), targets)
    }

    @Test
    fun `collapsed group menu is only shown for collapsed groups in narrow mode`() {
        assertTrue(
            editorDeckShouldShowCollapsedGroupMenu(
                verticalGroups = true,
                renderExpanded = false,
                groupCollapsed = true,
                entryCount = 2,
            ),
        )
        assertFalse(
            editorDeckShouldShowCollapsedGroupMenu(
                verticalGroups = true,
                renderExpanded = false,
                groupCollapsed = false,
                entryCount = 2,
            ),
        )
        assertFalse(
            editorDeckShouldShowCollapsedGroupMenu(
                verticalGroups = true,
                renderExpanded = true,
                groupCollapsed = true,
                entryCount = 2,
            ),
        )
        assertFalse(
            editorDeckShouldShowCollapsedGroupMenu(
                verticalGroups = true,
                renderExpanded = false,
                groupCollapsed = true,
                entryCount = 0,
            ),
        )
        assertFalse(
            editorDeckShouldShowCollapsedGroupMenu(
                verticalGroups = false,
                renderExpanded = false,
                groupCollapsed = true,
                entryCount = 2,
            ),
        )
    }

    @Test
    fun `hover popup is anchored beside narrow icon`() {
        assertEquals(
            44,
            editorDeckHoverPopupX(
                anchor = ToolWindowAnchor.LEFT,
                componentWidth = 44,
                popupWidth = 160,
            ),
        )
        assertEquals(
            -160,
            editorDeckHoverPopupX(
                anchor = ToolWindowAnchor.RIGHT,
                componentWidth = 44,
                popupWidth = 160,
            ),
        )
    }

    @Test
    fun `narrow group lists do not reserve scrollbar width`() {
        assertEquals(
            javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            editorDeckGroupVerticalScrollBarPolicy(renderExpanded = false),
        )
        assertEquals(
            javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            editorDeckGroupVerticalScrollBarPolicy(renderExpanded = true),
        )
    }

    @Test
    fun `narrow rows keep collapsed width for stable icon alignment`() {
        assertEquals(
            32,
            editorDeckRowPreferredWidth(renderExpanded = false, collapsedWidth = 32),
        )
        assertEquals(
            10,
            editorDeckRowPreferredWidth(renderExpanded = true, collapsedWidth = 32),
        )
    }

    @Test
    fun `expanded rows align close column with group disclosure column`() {
        assertEquals(editorDeckGroupHeaderLeftInset(), editorDeckExpandedRowLeftInset())
        assertEquals(6, editorDeckExpandedRowLeftInset())
    }

    @Test
    fun `group body heights follow stored height weights`() {
        val heights = editorDeckDistributedGroupBodyHeights(
            bodyHeightTotal = 120,
            minimumBodyHeight = 24,
            heightWeights = listOf(1, 3),
        )

        assertEquals(listOf(42, 78), heights)
    }

    @Test
    fun `dragging a group divider adjusts adjacent height weights within minimum`() {
        assertEquals(
            listOf(176, 24),
            editorDeckAdjustedGroupHeightWeights(
                heightWeights = listOf(100, 100),
                dividerIndex = 0,
                delta = 120,
                minimumWeight = 24,
            ),
        )
        assertEquals(
            listOf(24, 176),
            editorDeckAdjustedGroupHeightWeights(
                heightWeights = listOf(100, 100),
                dividerIndex = 0,
                delta = -120,
                minimumWeight = 24,
            ),
        )
    }

    @Test
    fun `default collapsed width matches the narrow manual resize target`() {
        assertEquals(32, editorDeckDefaultCollapsedWidth())
    }

    @Test
    fun `deck size constraints keep preferred width but allow manual narrowing`() {
        val constraints = editorDeckSizeConstraints(targetWidth = 360, collapsedWidth = 32)

        assertEquals(32, constraints.minimumWidth)
        assertEquals(360, constraints.preferredWidth)
        assertEquals(Int.MAX_VALUE, constraints.maximumWidth)
    }

    @Test
    fun `render width follows outer decorator before inner component widths`() {
        assertEquals(
            44,
            editorDeckActualRenderWidth(
                panelWidth = 360,
                viewportWidth = 44,
                componentWidth = 360,
                decoratorWidth = 44,
            ),
        )
        assertEquals(
            420,
            editorDeckActualRenderWidth(
                panelWidth = 360,
                viewportWidth = 360,
                componentWidth = 420,
                decoratorWidth = 420,
            ),
        )
    }

    @Test
    fun `render width follows outer decorator when inner viewport is stale after resizing wider`() {
        assertEquals(
            360,
            editorDeckActualRenderWidth(
                panelWidth = 44,
                viewportWidth = 44,
                componentWidth = 360,
                decoratorWidth = 360,
            ),
        )
    }

    @Test
    fun `render width follows outer decorator when inner viewport is stale after resizing narrower`() {
        assertEquals(
            44,
            editorDeckActualRenderWidth(
                panelWidth = 360,
                viewportWidth = 360,
                componentWidth = 360,
                decoratorWidth = 44,
            ),
        )
    }

    @Test
    fun `width toggle is only shown for vertical groups`() {
        assertTrue(editorDeckShouldShowWidthToggle(verticalGroups = true))
        assertFalse(editorDeckShouldShowWidthToggle(verticalGroups = false))
    }

    @Test
    fun `horizontal group mode renders expanded regardless of narrow width`() {
        assertFalse(
            editorDeckShouldRenderExpanded(
                verticalGroups = true,
                expanded = true,
                actualWidth = 120,
                expandedRenderWidthThreshold = 160,
            ),
        )
        assertTrue(
            editorDeckShouldRenderExpanded(
                verticalGroups = false,
                expanded = true,
                actualWidth = 120,
                expandedRenderWidthThreshold = 160,
            ),
        )
    }

    @Test
    fun `manual resize to wide width restores expanded width state`() {
        val state = editorDeckWidthStateAfterResize(
            verticalGroups = true,
            expanded = false,
            manuallyCollapsed = true,
            actualWidth = 220,
            expandedRenderWidthThreshold = 160,
        )

        assertTrue(state.expanded)
        assertFalse(state.manuallyCollapsed)
        assertTrue(state.renderExpanded)
    }

    @Test
    fun `manual resize to narrow width switches width state to collapsed`() {
        val state = editorDeckWidthStateAfterResize(
            verticalGroups = true,
            expanded = true,
            manuallyCollapsed = false,
            actualWidth = 120,
            expandedRenderWidthThreshold = 160,
        )

        assertFalse(state.expanded)
        assertTrue(state.manuallyCollapsed)
        assertFalse(state.renderExpanded)
    }

    @Test
    fun `horizontal group mode keeps expanded width state after resize`() {
        val state = editorDeckWidthStateAfterResize(
            verticalGroups = false,
            expanded = false,
            manuallyCollapsed = true,
            actualWidth = 120,
            expandedRenderWidthThreshold = 160,
        )

        assertTrue(state.expanded)
        assertFalse(state.manuallyCollapsed)
        assertTrue(state.renderExpanded)
    }

    @Test
    fun `content ui type is only forced when editor deck group mode changes`() {
        assertTrue(editorDeckShouldApplyContentUiType(previousVerticalMode = null, verticalGroups = false))
        assertTrue(editorDeckShouldApplyContentUiType(previousVerticalMode = true, verticalGroups = false))
        assertFalse(editorDeckShouldApplyContentUiType(previousVerticalMode = false, verticalGroups = false))
    }

    @Test
    fun `horizontal groups keep fitting tabs and overflow the rest`() {
        val visibleCount = editorDeckVisibleHorizontalGroupCount(
            availableWidth = 190,
            tabWidths = listOf(70, 70, 70),
            overflowButtonWidth = 28,
        )

        assertEquals(2, visibleCount)
    }

    @Test
    fun `horizontal group selection prefers active editor group when no selected group is stored`() {
        val groupId = editorDeckSelectedHorizontalGroupId(
            selectedGroupId = null,
            activeGroupId = "backend",
            groupIds = listOf("ungrouped", "backend", "frontend"),
            fallbackGroupId = "ungrouped",
        )

        assertEquals("backend", groupId)
    }

    @Test
    fun `horizontal group selection keeps stored group before active editor group`() {
        val groupId = editorDeckSelectedHorizontalGroupId(
            selectedGroupId = "frontend",
            activeGroupId = "backend",
            groupIds = listOf("ungrouped", "backend", "frontend"),
            fallbackGroupId = "ungrouped",
        )

        assertEquals("frontend", groupId)
    }

    @Test
    fun `title new group action stays enabled in both group modes`() {
        assertTrue(editorDeckCanCreateGroupFromTitle(verticalGroups = true))
        assertTrue(editorDeckCanCreateGroupFromTitle(verticalGroups = false))
    }

    @Test
    fun `group drag drops only target group headers`() {
        assertTrue(
            editorDeckCanDropGroupOnTarget(
                movingGroupId = "backend",
                targetGroupId = "frontend",
                targetFileUrl = null,
                ungroupedGroupId = "ungrouped",
            ),
        )
        assertFalse(
            editorDeckCanDropGroupOnTarget(
                movingGroupId = "backend",
                targetGroupId = "frontend",
                targetFileUrl = "file://Service.kt",
                ungroupedGroupId = "ungrouped",
            ),
        )
        assertFalse(
            editorDeckCanDropGroupOnTarget(
                movingGroupId = "backend",
                targetGroupId = "backend",
                targetFileUrl = null,
                ungroupedGroupId = "ungrouped",
            ),
        )
        assertTrue(
            editorDeckCanDropGroupOnTarget(
                movingGroupId = "backend",
                targetGroupId = "ungrouped",
                targetFileUrl = null,
                ungroupedGroupId = "ungrouped",
            ),
        )
        assertFalse(
            editorDeckCanDropGroupOnTarget(
                movingGroupId = "ungrouped",
                targetGroupId = "ungrouped",
                targetFileUrl = null,
                ungroupedGroupId = "ungrouped",
            ),
        )
    }

    @Test
    fun `drop point in bottom half inserts after target item`() {
        assertFalse(editorDeckDropAfterTarget(dropY = 9, targetHeight = 20))
        assertTrue(editorDeckDropAfterTarget(dropY = 10, targetHeight = 20))
    }

    @Test
    fun `group can drop after the last display group`() {
        val order = listOf("ungrouped", "backend", "frontend")

        val index = editorDeckDropIndex(
            currentOrder = order,
            targetId = "frontend",
            movingIds = listOf("ungrouped"),
            dropAfterTarget = true,
        )

        assertEquals(2, index)
    }

    @Test
    fun `group can still drop before a target display group`() {
        val order = listOf("ungrouped", "backend", "frontend")

        val index = editorDeckDropIndex(
            currentOrder = order,
            targetId = "frontend",
            movingIds = listOf("ungrouped"),
            dropAfterTarget = false,
        )

        assertEquals(1, index)
    }

    @Test
    fun `drag image uses a translucent ghost opacity`() {
        assertTrue(editorDeckDragImageOpacity() in 0.45f..0.85f)
    }

    @Test
    fun `horizontal move current group menu is shown only for movable current user groups`() {
        assertTrue(
            editorDeckShouldShowHorizontalMoveCurrentGroupMenu(
                verticalGroups = false,
                currentGroupId = "backend",
                groupIds = listOf("backend", "frontend"),
                ungroupedGroupId = "ungrouped",
            ),
        )
        assertFalse(
            editorDeckShouldShowHorizontalMoveCurrentGroupMenu(
                verticalGroups = true,
                currentGroupId = "backend",
                groupIds = listOf("backend", "frontend"),
                ungroupedGroupId = "ungrouped",
            ),
        )
        assertTrue(
            editorDeckShouldShowHorizontalMoveCurrentGroupMenu(
                verticalGroups = false,
                currentGroupId = "ungrouped",
                groupIds = listOf("ungrouped", "backend", "frontend"),
                ungroupedGroupId = "ungrouped",
            ),
        )
        assertTrue(
            editorDeckShouldShowHorizontalMoveCurrentGroupMenu(
                verticalGroups = false,
                currentGroupId = "ungrouped",
                groupIds = listOf("ungrouped", "backend"),
                ungroupedGroupId = "ungrouped",
            ),
        )
        assertFalse(
            editorDeckShouldShowHorizontalMoveCurrentGroupMenu(
                verticalGroups = false,
                currentGroupId = "backend",
                groupIds = listOf("backend"),
                ungroupedGroupId = "ungrouped",
            ),
        )
    }

    @Test
    fun `horizontal empty groups do not show the empty editor placeholder`() {
        assertTrue(editorDeckShouldShowEmptyEditorsRow(verticalGroups = true, hasOpenEditors = false))
        assertFalse(editorDeckShouldShowEmptyEditorsRow(verticalGroups = false, hasOpenEditors = false))
    }

    @Test
    fun `horizontal editor popup puts current group move before editor actions`() {
        assertEquals(
            listOf(
                EditorDeckEditorPopupSection.CURRENT_GROUP_MOVE,
                EditorDeckEditorPopupSection.SEPARATOR,
                EditorDeckEditorPopupSection.EDITOR_ACTIONS,
            ),
            editorDeckEditorPopupLeadingSections(verticalGroups = false),
        )
        assertEquals(
            listOf(EditorDeckEditorPopupSection.EDITOR_ACTIONS),
            editorDeckEditorPopupLeadingSections(verticalGroups = true),
        )
    }

    @Test
    fun `ctrl clicking an unselected row adds it to the selection`() {
        val selection = editorDeckSelectionAfterCtrlClick(
            selectedUrls = linkedSetOf("file://A.java"),
            clickedUrl = "file://B.java",
        )

        assertEquals(linkedSetOf("file://A.java", "file://B.java"), selection)
    }

    @Test
    fun `ctrl clicking a selected row removes it from the selection`() {
        val selection = editorDeckSelectionAfterCtrlClick(
            selectedUrls = linkedSetOf("file://A.java", "file://B.java"),
            clickedUrl = "file://B.java",
        )

        assertEquals(linkedSetOf("file://A.java"), selection)
    }

    @Test
    fun `popup action keeps multi selection when clicked row is selected`() {
        val urls = editorDeckPopupActionUrls(
            selectedUrls = linkedSetOf("file://A.java", "file://B.java"),
            clickedUrl = "file://B.java",
        )

        assertEquals(listOf("file://A.java", "file://B.java"), urls)
    }

    @Test
    fun `popup action falls back to clicked row outside current selection`() {
        val urls = editorDeckPopupActionUrls(
            selectedUrls = linkedSetOf("file://A.java", "file://B.java"),
            clickedUrl = "file://C.java",
        )

        assertEquals(listOf("file://C.java"), urls)
    }

    @Test
    fun `active selected row keeps active background and shows selection marker`() {
        val visual = editorDeckRowVisualState(
            highlighted = false,
            active = true,
            selected = true,
            hovered = false,
        )

        assertEquals(EditorDeckRowBackgroundKind.ACTIVE, visual.backgroundKind)
        assertTrue(visual.showSelectionMarker)
    }

    @Test
    fun `selected inactive row uses selected background and selection marker`() {
        val visual = editorDeckRowVisualState(
            highlighted = false,
            active = false,
            selected = true,
            hovered = true,
        )

        assertEquals(EditorDeckRowBackgroundKind.SELECTED, visual.backgroundKind)
        assertTrue(visual.showSelectionMarker)
    }

    @Test
    fun `active unselected row does not show selection marker`() {
        val visual = editorDeckRowVisualState(
            highlighted = false,
            active = true,
            selected = false,
            hovered = false,
        )

        assertEquals(EditorDeckRowBackgroundKind.ACTIVE, visual.backgroundKind)
        assertFalse(visual.showSelectionMarker)
    }
}
