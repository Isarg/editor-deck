package io.github.isarg.editordeck.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenEditorsStateModelTest {
    @Test
    fun `new files are kept at root level after reconciliation`() {
        val state = OpenEditorsSettingsState()
        val model = OpenEditorsStateModel(state)

        model.reconcileOpenFiles(listOf("file://A.java", "file://B.java"))

        assertEquals(listOf("file://A.java", "file://B.java"), state.rootFileUrls)
        assertEquals(emptyList<EditorGroupState>(), state.groups)
        assertEquals(true, state.verticalGroups)
        assertEquals(false, state.showPathTooltips)
    }

    @Test
    fun `root files are exposed as the ungrouped group`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://A.java", "file://B.java"))
        val model = OpenEditorsStateModel(state)

        val ungrouped = model.ungroupedGroup()

        assertEquals(OpenEditorsStateModel.UNGROUPED_GROUP_ID, ungrouped.id)
        assertEquals("Ungrouped", ungrouped.name)
        assertEquals(listOf("file://A.java", "file://B.java"), ungrouped.fileUrls)
    }

    @Test
    fun `display groups include ungrouped first and user groups after it`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://A.java"))
        val model = OpenEditorsStateModel(state)
        val first = model.createGroup("First")
        val second = model.createGroup("Second")

        val groups = model.displayGroups()

        assertEquals(
            listOf(OpenEditorsStateModel.UNGROUPED_GROUP_ID, first.id, second.id),
            groups.map { it.id },
        )
    }

    @Test
    fun `root file resolves to the ungrouped group`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://A.java"))
        val model = OpenEditorsStateModel(state)

        assertEquals(OpenEditorsStateModel.UNGROUPED_GROUP_ID, model.groupForFile("file://A.java")?.id)
    }

    @Test
    fun `ungrouped collapsed state is persisted separately from user groups`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://A.java"))
        val model = OpenEditorsStateModel(state)

        model.setGroupCollapsed(OpenEditorsStateModel.UNGROUPED_GROUP_ID, true)

        assertEquals(true, state.ungroupedCollapsed)
        assertEquals(true, model.ungroupedGroup().collapsed)
    }

    @Test
    fun `group body height weights are persisted for ungrouped and user groups`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://A.java"))
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")

        model.setGroupBodyHeightWeight(OpenEditorsStateModel.UNGROUPED_GROUP_ID, 220)
        model.setGroupBodyHeightWeight(group.id, 80)

        assertEquals(220, state.ungroupedBodyHeightWeight)
        assertEquals(220, model.ungroupedGroup().bodyHeightWeight)
        assertEquals(80, group.bodyHeightWeight)
    }

    @Test
    fun `pin state can be toggled for an editor url`() {
        val state = OpenEditorsSettingsState()
        val model = OpenEditorsStateModel(state)

        model.setPinned("file://A.java", true)
        model.setPinned("file://B.java", false)
        model.setPinned("file://A.java", false)

        assertEquals(emptyList<String>(), state.pinnedFileUrls)
    }

    @Test
    fun `pinning root files moves them after existing pinned files`() {
        val state = OpenEditorsSettingsState(
            rootFileUrls = mutableListOf("file://A.java", "file://B.java", "file://C.java"),
        )
        val model = OpenEditorsStateModel(state)

        model.setPinned("file://B.java", true)
        model.setPinned("file://C.java", true)

        assertEquals(listOf("file://B.java", "file://C.java", "file://A.java"), state.rootFileUrls)
        assertEquals(listOf("file://B.java", "file://C.java"), state.pinnedFileUrls)
    }

    @Test
    fun `unpinning root files moves them to the first unpinned position`() {
        val state = OpenEditorsSettingsState(
            rootFileUrls = mutableListOf("file://A.java", "file://B.java", "file://C.java"),
        )
        val model = OpenEditorsStateModel(state)
        model.setPinned("file://B.java", true)
        model.setPinned("file://C.java", true)

        model.setPinned("file://B.java", false)

        assertEquals(listOf("file://C.java", "file://B.java", "file://A.java"), state.rootFileUrls)
        assertEquals(listOf("file://C.java"), state.pinnedFileUrls)
    }

    @Test
    fun `pinning files reorders only their current group`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://Root.java"))
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")
        group.fileUrls.addAll(listOf("file://A.java", "file://B.java", "file://C.java"))

        model.setPinned("file://B.java", true)
        model.setPinned("file://C.java", true)
        model.setPinned("file://B.java", false)

        assertEquals(listOf("file://Root.java"), state.rootFileUrls)
        assertEquals(listOf("file://C.java", "file://B.java", "file://A.java"), group.fileUrls)
    }

    @Test
    fun `dissolving group moves files back to root preserving relative order`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://Root.java"))
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")
        model.moveFileToGroup("file://A.java", group.id)
        model.moveFileToGroup("file://B.java", group.id)

        model.dissolveGroup(group.id)

        assertEquals(listOf("file://Root.java", "file://A.java", "file://B.java"), state.rootFileUrls)
        assertEquals(emptyList<EditorGroupState>(), state.groups)
    }

    @Test
    fun `closing group returns files to close and removes the group`() {
        val state = OpenEditorsSettingsState()
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Dependency")
        model.moveFileToGroup("file://DepA.class", group.id)
        model.moveFileToGroup("file://DepB.class", group.id)

        val filesToClose = model.closeGroup(group.id)

        assertEquals(listOf("file://DepA.class", "file://DepB.class"), filesToClose)
        assertEquals(emptyList<EditorGroupState>(), state.groups)
        assertEquals(emptyList<String>(), state.rootFileUrls)
    }

    @Test
    fun `files can move between root and groups and be reordered`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://A.java", "file://B.java"))
        val model = OpenEditorsStateModel(state)
        val first = model.createGroup("First")
        val second = model.createGroup("Second")

        model.moveFileToGroup("file://A.java", first.id)
        model.moveFileToGroup("file://B.java", second.id)
        model.moveFileToRoot("file://B.java", 0)
        model.reorderGroups(listOf(second.id, first.id))

        assertEquals(listOf("file://B.java"), state.rootFileUrls)
        assertEquals(listOf(second.id, first.id), state.groups.map { it.id })
        assertEquals(listOf("file://A.java"), state.groups.last().fileUrls)
    }

    @Test
    fun `creating a group for a file moves that file into the new group`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://A.java", "file://B.java"))
        val model = OpenEditorsStateModel(state)

        val group = model.createGroupWithFiles("Scratch", listOf("file://B.java"))

        assertEquals(listOf("file://A.java"), state.rootFileUrls)
        assertEquals("Scratch", group.name)
        assertEquals(listOf("file://B.java"), group.fileUrls)
    }

    @Test
    fun `multiple files can move to a target group index preserving drag order`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://A.java", "file://B.java"))
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")
        group.fileUrls.addAll(listOf("file://C.java", "file://D.java"))

        model.moveFilesToGroup(listOf("file://B.java", "file://C.java"), group.id, 1)

        assertEquals(listOf("file://A.java"), state.rootFileUrls)
        assertEquals(listOf("file://D.java", "file://B.java", "file://C.java"), group.fileUrls)
    }

    @Test
    fun `moving a pinned file to a group places it after existing pinned files`() {
        val state = OpenEditorsSettingsState(
            pinnedFileUrls = mutableListOf("file://PinnedA.java", "file://PinnedB.java"),
            rootFileUrls = mutableListOf("file://PinnedB.java", "file://Root.java"),
        )
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")
        group.fileUrls.addAll(listOf("file://PinnedA.java", "file://Unpinned.java"))

        model.moveFileToGroup("file://PinnedB.java", group.id)

        assertEquals(listOf("file://Root.java"), state.rootFileUrls)
        assertEquals(listOf("file://PinnedA.java", "file://PinnedB.java", "file://Unpinned.java"), group.fileUrls)
    }

    @Test
    fun `multiple files can move back to root before a target index`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://Root.java", "file://Tail.java"))
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")
        group.fileUrls.addAll(listOf("file://A.java", "file://B.java"))

        model.moveFilesToRoot(listOf("file://A.java", "file://B.java"), 1)

        assertEquals(listOf("file://Root.java", "file://A.java", "file://B.java", "file://Tail.java"), state.rootFileUrls)
        assertEquals(emptyList<String>(), group.fileUrls)
    }

    @Test
    fun `moving a pinned file back to root places it after existing pinned files`() {
        val state = OpenEditorsSettingsState(
            pinnedFileUrls = mutableListOf("file://RootPinned.java", "file://GroupPinned.java"),
            rootFileUrls = mutableListOf("file://RootPinned.java", "file://Root.java"),
        )
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")
        group.fileUrls.addAll(listOf("file://GroupPinned.java", "file://Group.java"))

        model.moveFileToRoot("file://GroupPinned.java")

        assertEquals(listOf("file://RootPinned.java", "file://GroupPinned.java", "file://Root.java"), state.rootFileUrls)
        assertEquals(listOf("file://Group.java"), group.fileUrls)
    }

    @Test
    fun `moving files to ungrouped group moves them back to root`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://Root.java"))
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")
        group.fileUrls.addAll(listOf("file://A.java"))

        model.moveFilesToGroup(listOf("file://A.java"), OpenEditorsStateModel.UNGROUPED_GROUP_ID, 0)

        assertEquals(listOf("file://A.java", "file://Root.java"), state.rootFileUrls)
        assertEquals(emptyList<String>(), group.fileUrls)
    }

    @Test
    fun `dissolving a group keeps moved pinned files in the root pinned area`() {
        val state = OpenEditorsSettingsState(
            pinnedFileUrls = mutableListOf("file://RootPinned.java", "file://GroupPinned.java"),
            rootFileUrls = mutableListOf("file://RootPinned.java", "file://Root.java"),
        )
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")
        group.fileUrls.addAll(listOf("file://Group.java", "file://GroupPinned.java"))

        model.dissolveGroup(group.id)

        assertEquals(
            listOf("file://RootPinned.java", "file://GroupPinned.java", "file://Root.java", "file://Group.java"),
            state.rootFileUrls,
        )
        assertEquals(emptyList<EditorGroupState>(), state.groups)
    }

    @Test
    fun `multiple groups can move before a target index preserving drag order`() {
        val state = OpenEditorsSettingsState()
        val model = OpenEditorsStateModel(state)
        val first = model.createGroup("First")
        val second = model.createGroup("Second")
        val third = model.createGroup("Third")
        val fourth = model.createGroup("Fourth")

        model.moveGroups(listOf(second.id, fourth.id), 1)

        assertEquals(listOf(first.id, second.id, fourth.id, third.id), state.groups.map { it.id })
    }

    @Test
    fun `a group can move after another group`() {
        val state = OpenEditorsSettingsState()
        val model = OpenEditorsStateModel(state)
        val first = model.createGroup("First")
        val second = model.createGroup("Second")
        val third = model.createGroup("Third")

        model.moveGroupAfter(first.id, third.id)
        model.moveGroupAfter(third.id, second.id)

        assertEquals(listOf(second.id, third.id, first.id), state.groups.map { it.id })
    }

    @Test
    fun `ungrouped group can move after a user group in display order`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://Root.java"))
        val model = OpenEditorsStateModel(state)
        val first = model.createGroup("First")
        val second = model.createGroup("Second")

        model.moveGroupAfter(OpenEditorsStateModel.UNGROUPED_GROUP_ID, first.id)

        assertEquals(
            listOf(first.id, OpenEditorsStateModel.UNGROUPED_GROUP_ID, second.id),
            model.displayGroups().map { it.id },
        )
        assertEquals(listOf(first.id, second.id), state.groups.map { it.id })
    }

    @Test
    fun `ungrouped group can move to the end of display order`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://Root.java"))
        val model = OpenEditorsStateModel(state)
        val first = model.createGroup("First")
        val second = model.createGroup("Second")

        model.moveDisplayGroups(listOf(OpenEditorsStateModel.UNGROUPED_GROUP_ID), 2)

        assertEquals(
            listOf(first.id, second.id, OpenEditorsStateModel.UNGROUPED_GROUP_ID),
            model.displayGroups().map { it.id },
        )
        assertEquals(listOf(first.id, second.id), state.groups.map { it.id })
    }

    @Test
    fun `closed files are removed from root and groups`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://Root.java", "file://Closed.java"))
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")
        model.moveFileToGroup("file://Open.java", group.id)
        model.moveFileToGroup("file://Gone.java", group.id)

        model.reconcileOpenFiles(listOf("file://Root.java", "file://Open.java"))

        assertEquals(listOf("file://Root.java"), state.rootFileUrls)
        assertEquals(listOf("file://Open.java"), state.groups.single().fileUrls)
    }

    @Test
    fun `replacing an open file preserves its group location`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://Root.java"))
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Preview")
        group.fileUrls.addAll(listOf("file://Old.java"))

        model.replaceOpenFile("file://Old.java", "file://New.java")

        assertEquals(listOf("file://Root.java"), state.rootFileUrls)
        assertEquals(listOf("file://New.java"), group.fileUrls)
    }

    @Test
    fun `replacing an open file removes duplicate new url from root`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://New.java"))
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Preview")
        group.fileUrls.addAll(listOf("file://Old.java"))

        model.replaceOpenFile("file://Old.java", "file://New.java")

        assertEquals(emptyList<String>(), state.rootFileUrls)
        assertEquals(listOf("file://New.java"), group.fileUrls)
    }

    @Test
    fun `replacing a trailing old file with an existing new file in the same group does not overflow`() {
        val state = OpenEditorsSettingsState()
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Preview")
        group.fileUrls.addAll(
            listOf(
                "file://A.java",
                "file://B.java",
                "file://C.java",
                "file://New.java",
                "file://Old.java",
            ),
        )

        model.replaceOpenFile("file://Old.java", "file://New.java")

        assertEquals(listOf("file://A.java", "file://B.java", "file://C.java", "file://New.java"), group.fileUrls)
    }

    @Test
    fun `replacing a pinned preview file preserves pinned state`() {
        val state = OpenEditorsSettingsState(pinnedFileUrls = mutableListOf("file://Old.java"))
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Preview")
        group.fileUrls.add("file://Old.java")

        model.replaceOpenFile("file://Old.java", "file://New.java")

        assertEquals(listOf("file://New.java"), group.fileUrls)
        assertEquals(listOf("file://New.java"), state.pinnedFileUrls)
    }

    @Test
    fun `replacing an unknown old file leaves existing new url untouched`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://New.java"))
        val model = OpenEditorsStateModel(state)

        model.replaceOpenFile("file://Missing.java", "file://New.java")

        assertEquals(listOf("file://New.java"), state.rootFileUrls)
    }

    @Test
    fun `group counts separate pinned and unpinned files`() {
        val state = OpenEditorsSettingsState(
            pinnedFileUrls = mutableListOf("file://A.java", "file://C.java"),
        )
        val model = OpenEditorsStateModel(state)
        val group = model.createGroup("Backend")
        group.fileUrls.addAll(listOf("file://A.java", "file://B.java", "file://C.java", "file://D.java"))

        val counts = model.groupFileCounts(group)

        assertEquals(GroupFileCounts(pinned = 2, unpinned = 2), counts)
    }

    @Test
    fun `group counts render as pinned and unpinned numbers`() {
        val counts = GroupFileCounts(pinned = 3, unpinned = 7)

        assertEquals("3|7", counts.summaryText())
    }

    @Test
    fun `collapsing all groups includes ungrouped and user groups`() {
        val state = OpenEditorsSettingsState(rootFileUrls = mutableListOf("file://Root.java"))
        val model = OpenEditorsStateModel(state)
        val first = model.createGroup("First")
        val second = model.createGroup("Second")
        first.collapsed = false
        second.collapsed = false

        model.collapseAllGroups()

        assertEquals(true, state.ungroupedCollapsed)
        assertEquals(listOf(true, true), state.groups.map { it.collapsed })
    }

}
