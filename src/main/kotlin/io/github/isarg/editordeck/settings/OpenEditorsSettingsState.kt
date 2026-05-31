package io.github.isarg.editordeck.settings

import io.github.isarg.editordeck.EditorDeckBundle
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.util.UUID

const val DEFAULT_GROUP_BODY_HEIGHT_WEIGHT: Int = 160
const val MIN_GROUP_BODY_HEIGHT_WEIGHT: Int = 24

// Serialized by IntelliJ's PersistentStateComponent. Keep fields mutable and simple so
// the XML serializer can restore state across IDE restarts.
data class EditorGroupState(
    var id: String = UUID.randomUUID().toString(),
    var name: String = EditorDeckBundle.message("group.default.name"),
    var collapsed: Boolean = false,
    var bodyHeightWeight: Int = DEFAULT_GROUP_BODY_HEIGHT_WEIGHT,
    var fileUrls: MutableList<String> = mutableListOf(),
)

/**
 * Persistent plugin settings stored per project.
 */
data class OpenEditorsSettingsState(
    var showRelativePath: Boolean = true,
    var showLibraryFiles: Boolean = true,
    var showPathTooltips: Boolean = false,
    var verticalGroups: Boolean = true,
    var selectedHorizontalGroupId: String? = null,
    var autoScrollToActive: Boolean = true,
    var ungroupedCollapsed: Boolean = false,
    var ungroupedBodyHeightWeight: Int = DEFAULT_GROUP_BODY_HEIGHT_WEIGHT,
    // Display order includes the synthetic Ungrouped ID. User groups still live in
    // groups so legacy state and serializer output stay straightforward.
    var groupOrder: MutableList<String> = mutableListOf(),
    var pinnedFileUrls: MutableList<String> = mutableListOf(),
    var rootFileUrls: MutableList<String> = mutableListOf(),
    var groups: MutableList<EditorGroupState> = mutableListOf(),
)

/**
 * Compact count model used by group headers.
 */
data class GroupFileCounts(
    val pinned: Int,
    val unpinned: Int,
) {
    val total: Int
        get() = pinned + unpinned

    fun summaryText(): String = "$pinned|$unpinned"
}

/**
 * Mutating facade over [OpenEditorsSettingsState].
 *
 * UI code should use this model instead of editing lists directly because it keeps the
 * synthetic Ungrouped group, pinned ordering, and persisted display order consistent.
 */
class OpenEditorsStateModel(private val state: OpenEditorsSettingsState) {
    // Ungrouped is a synthetic group backed directly by rootFileUrls; do not put it in
    // state.groups or the serializer would persist a duplicate container.
    fun ungroupedGroup(): EditorGroupState =
        EditorGroupState(
            id = UNGROUPED_GROUP_ID,
            name = EditorDeckBundle.message("group.ungrouped.name"),
            collapsed = state.ungroupedCollapsed,
            bodyHeightWeight = groupBodyHeightWeight(UNGROUPED_GROUP_ID),
            fileUrls = state.rootFileUrls,
        )

    fun displayGroups(): List<EditorGroupState> =
        normalizedGroupOrderIds().mapNotNull { groupId ->
            if (groupId == UNGROUPED_GROUP_ID) {
                ungroupedGroup()
            } else {
                findGroup(groupId)
            }
        }

    fun createGroup(name: String): EditorGroupState {
        val group = EditorGroupState(name = name)
        state.groups.add(group)
        return group
    }

    fun createGroupWithFiles(name: String, fileUrls: List<String>): EditorGroupState {
        val group = createGroup(name)
        moveFilesToGroup(fileUrls, group.id)
        return group
    }

    fun isPinned(fileUrl: String): Boolean =
        fileUrl in state.pinnedFileUrls

    fun setPinned(fileUrl: String, pinned: Boolean) {
        if (isPinned(fileUrl) == pinned) return
        val container = findFileContainer(fileUrl)
        if (pinned) {
            state.pinnedFileUrls.add(fileUrl)
            // Pinning moves within the current container only. IDEA's native editor tab
            // pinning is synchronized separately by OpenEditorsService.
            container?.moveAfterLastPinned(fileUrl)
        } else {
            state.pinnedFileUrls.remove(fileUrl)
            container?.moveToFirstUnpinned(fileUrl)
        }
    }

    fun renameGroup(groupId: String, name: String) {
        findGroup(groupId)?.name = name
    }

    fun setGroupCollapsed(groupId: String, collapsed: Boolean) {
        if (groupId == UNGROUPED_GROUP_ID) {
            state.ungroupedCollapsed = collapsed
        } else {
            findGroup(groupId)?.collapsed = collapsed
        }
    }

    fun groupBodyHeightWeight(groupId: String): Int =
        if (groupId == UNGROUPED_GROUP_ID) {
            normalizedBodyHeightWeight(state.ungroupedBodyHeightWeight)
        } else {
            normalizedBodyHeightWeight(findGroup(groupId)?.bodyHeightWeight ?: DEFAULT_GROUP_BODY_HEIGHT_WEIGHT)
        }

    fun setGroupBodyHeightWeight(groupId: String, weight: Int) {
        val normalized = normalizedBodyHeightWeight(weight)
        if (groupId == UNGROUPED_GROUP_ID) {
            state.ungroupedBodyHeightWeight = normalized
        } else {
            findGroup(groupId)?.bodyHeightWeight = normalized
        }
    }

    fun collapseAllGroups() {
        state.ungroupedCollapsed = true
        state.groups.forEach { it.collapsed = true }
    }

    fun dissolveGroup(groupId: String) {
        val group = removeGroup(groupId) ?: return
        state.rootFileUrls.addAll(group.fileUrls)
        // Dissolving should not demote pinned files to the bottom of Ungrouped.
        state.rootFileUrls.movePinnedBeforeUnpinned()
    }

    fun closeGroup(groupId: String): List<String> {
        val group = removeGroup(groupId) ?: return emptyList()
        return group.fileUrls.toList()
    }

    fun moveFileToGroup(fileUrl: String, groupId: String, index: Int? = null) {
        moveFilesToGroup(listOf(fileUrl), groupId, index)
    }

    fun moveFileToRoot(fileUrl: String, index: Int? = null) {
        moveFilesToRoot(listOf(fileUrl), index)
    }

    fun moveFilesToGroup(fileUrls: List<String>, groupId: String, index: Int? = null) {
        if (groupId == UNGROUPED_GROUP_ID) {
            moveFilesToRoot(fileUrls, index)
            return
        }
        val group = findGroup(groupId) ?: return
        val uniqueUrls = fileUrls.distinct()
        uniqueUrls.forEach { removeFile(it) }
        group.fileUrls.addAllAtBounded(index, uniqueUrls)
        group.fileUrls.movePinnedBeforeUnpinned()
    }

    fun moveFilesToRoot(fileUrls: List<String>, index: Int? = null) {
        val uniqueUrls = fileUrls.distinct()
        uniqueUrls.forEach { removeFile(it) }
        state.rootFileUrls.addAllAtBounded(index, uniqueUrls)
        state.rootFileUrls.movePinnedBeforeUnpinned()
    }

    fun moveGroups(groupIds: List<String>, index: Int? = null) {
        val uniqueIds = groupIds.distinct()
        val moving = uniqueIds.mapNotNull { id -> findGroup(id) }
        if (moving.isEmpty()) return
        state.groups.removeAll(moving.toSet())
        state.groups.addAllAtBounded(index, moving)
        if (state.groupOrder.isNotEmpty()) {
            // Preserve Ungrouped's visual slot while applying legacy user-group reorders.
            applyGroupOrder(mergeDisplayOrderWithUserGroupOrder(state.groups.map { it.id }))
        }
    }

    fun moveDisplayGroups(groupIds: List<String>, index: Int? = null) {
        // Used by Tool Window drag/drop where Ungrouped participates as a first-class
        // visual group, even though it is not stored in state.groups.
        val currentOrder = normalizedGroupOrderIds()
        val uniqueIds = groupIds.distinct().filter { it in currentOrder }
        if (uniqueIds.isEmpty()) return
        val nextOrder = currentOrder.toMutableList()
        nextOrder.removeAll(uniqueIds.toSet())
        nextOrder.addAllAtBounded(index, uniqueIds)
        applyGroupOrder(nextOrder)
    }

    fun moveGroupAfter(groupId: String, targetGroupId: String) {
        if (groupId == targetGroupId) return
        val currentOrder = normalizedGroupOrderIds()
        if (groupId !in currentOrder || targetGroupId !in currentOrder) return
        val nextOrder = currentOrder.toMutableList()
        nextOrder.remove(groupId)
        val targetIndex = nextOrder.indexOf(targetGroupId)
        nextOrder.add((targetIndex + 1).coerceIn(0, nextOrder.size), groupId)
        applyGroupOrder(nextOrder)
    }

    fun reorderRootFiles(fileUrls: List<String>) {
        state.rootFileUrls.retainAndAppendKnown(fileUrls)
    }

    fun reorderGroupFiles(groupId: String, fileUrls: List<String>) {
        findGroup(groupId)?.fileUrls?.retainAndAppendKnown(fileUrls)
    }

    fun reorderGroups(groupIds: List<String>) {
        val byId = state.groups.associateBy { it.id }
        val ordered = groupIds.mapNotNull { byId[it] }
        val remaining = state.groups.filterNot { it.id in groupIds }
        state.groups.clear()
        state.groups.addAll(ordered + remaining)
    }

    fun reconcileOpenFiles(openFileUrls: List<String>) {
        // Reconciliation is intentionally additive for new URLs and destructive only for
        // closed URLs; user ordering is left intact for every file that remains open.
        val openSet = openFileUrls.toSet()
        state.rootFileUrls.removeAll { it !in openSet }
        state.groups.forEach { group -> group.fileUrls.removeAll { it !in openSet } }
        state.pinnedFileUrls.removeAll { it !in openSet }

        val known = state.rootFileUrls.toMutableSet()
        state.groups.forEach { known.addAll(it.fileUrls) }
        openFileUrls.filterNot { it in known }.forEach { state.rootFileUrls.add(it) }
    }

    fun replaceOpenFile(oldUrl: String, newUrl: String) {
        if (oldUrl == newUrl) return
        val location = findFileLocation(oldUrl) ?: return
        val newIndexInSameContainer = location.urls.indexOf(newUrl)
        // If the new preview URL already exists before the old URL, removing it first
        // shifts the insertion point left by one.
        val targetIndex = location.index - if (newIndexInSameContainer in 0 until location.index) 1 else 0
        replacePinnedUrl(oldUrl, newUrl)
        removeFile(oldUrl)
        removeFile(newUrl)
        location.urls.add(targetIndex.coerceIn(0, location.urls.size), newUrl)
    }

    fun groupFileCounts(group: EditorGroupState): GroupFileCounts {
        val pinned = group.fileUrls.count { it in state.pinnedFileUrls }
        return GroupFileCounts(pinned = pinned, unpinned = group.fileUrls.size - pinned)
    }

    fun groupForFile(fileUrl: String): EditorGroupState? =
        if (fileUrl in state.rootFileUrls) {
            ungroupedGroup()
        } else {
            state.groups.firstOrNull { fileUrl in it.fileUrls }
        }

    private fun removeFile(fileUrl: String) {
        state.rootFileUrls.remove(fileUrl)
        state.groups.forEach { it.fileUrls.remove(fileUrl) }
    }

    private fun findFileContainer(fileUrl: String): MutableList<String>? =
        findFileLocation(fileUrl)?.urls

    private fun findFileLocation(fileUrl: String): FileLocation? {
        val rootIndex = state.rootFileUrls.indexOf(fileUrl)
        if (rootIndex >= 0) return FileLocation(state.rootFileUrls, rootIndex)
        state.groups.forEach { group ->
            val index = group.fileUrls.indexOf(fileUrl)
            if (index >= 0) return FileLocation(group.fileUrls, index)
        }
        return null
    }

    private fun replacePinnedUrl(oldUrl: String, newUrl: String) {
        val oldIndex = state.pinnedFileUrls.indexOf(oldUrl)
        val newWasPinned = state.pinnedFileUrls.remove(newUrl)
        state.pinnedFileUrls.remove(oldUrl)
        when {
            oldIndex >= 0 -> state.pinnedFileUrls.add(oldIndex.coerceIn(0, state.pinnedFileUrls.size), newUrl)
            newWasPinned -> state.pinnedFileUrls.add(newUrl)
        }
    }

    private fun findGroup(groupId: String): EditorGroupState? =
        state.groups.firstOrNull { it.id == groupId }

    private fun removeGroup(groupId: String): EditorGroupState? {
        if (groupId == UNGROUPED_GROUP_ID) return null
        val group = findGroup(groupId) ?: return null
        state.groups.remove(group)
        state.groupOrder.remove(groupId)
        return group
    }

    private fun normalizedGroupOrderIds(): List<String> {
        // Filter stale IDs from older persisted state, then append any newly created
        // groups that are not yet represented in groupOrder.
        val knownIds = displayGroupIds()
        val known = knownIds.toSet()
        val normalized = linkedSetOf<String>()
        state.groupOrder.forEach { groupId ->
            if (groupId in known) {
                normalized.add(groupId)
            }
        }
        knownIds.forEach { groupId ->
            normalized.add(groupId)
        }
        return normalized.toList()
    }

    private fun displayGroupIds(): List<String> =
        listOf(UNGROUPED_GROUP_ID) + state.groups.map { it.id }

    private fun applyGroupOrder(groupIds: List<String>) {
        // Store the full display order, then mirror only user groups back into groups so
        // APIs that only know about real groups still see the same relative order.
        val knownIds = displayGroupIds()
        val known = knownIds.toSet()
        val normalized = linkedSetOf<String>()
        groupIds.forEach { groupId ->
            if (groupId in known) {
                normalized.add(groupId)
            }
        }
        knownIds.forEach { groupId ->
            normalized.add(groupId)
        }
        state.groupOrder.clear()
        state.groupOrder.addAll(normalized)

        val groupsById = state.groups.associateBy { it.id }
        val orderedGroups = normalized
            .filterNot { it == UNGROUPED_GROUP_ID }
            .mapNotNull { groupsById[it] }
        state.groups.clear()
        state.groups.addAll(orderedGroups)
    }

    private fun mergeDisplayOrderWithUserGroupOrder(userGroupIds: List<String>): List<String> {
        val userOrder = userGroupIds.toMutableList()
        val result = mutableListOf<String>()
        normalizedGroupOrderIds().forEach { groupId ->
            if (groupId == UNGROUPED_GROUP_ID) {
                result.add(groupId)
            } else if (userOrder.isNotEmpty()) {
                result.add(userOrder.removeAt(0))
            }
        }
        result.addAll(userOrder)
        return result
    }

    private fun normalizedBodyHeightWeight(weight: Int): Int =
        weight.coerceAtLeast(MIN_GROUP_BODY_HEIGHT_WEIGHT)

    private fun <T> MutableList<T>.addAllAtBounded(index: Int?, values: List<T>) {
        val target = index?.coerceIn(0, size) ?: size
        addAll(target, values)
    }

    private fun MutableList<String>.retainAndAppendKnown(orderedValues: List<String>) {
        val current = toSet()
        val ordered = orderedValues.filter { it in current }
        val remaining = filterNot { it in orderedValues }
        clear()
        addAll(ordered + remaining)
    }

    private fun MutableList<String>.moveAfterLastPinned(fileUrl: String) {
        if (!remove(fileUrl)) return
        val index = indexOfLast { it in state.pinnedFileUrls } + 1
        add(index.coerceIn(0, size), fileUrl)
    }

    private fun MutableList<String>.moveToFirstUnpinned(fileUrl: String) {
        if (!remove(fileUrl)) return
        val index = indexOfFirst { it !in state.pinnedFileUrls }
            .takeIf { it >= 0 }
            ?: size
        add(index.coerceIn(0, size), fileUrl)
    }

    private fun MutableList<String>.movePinnedBeforeUnpinned() {
        val pinned = filter { it in state.pinnedFileUrls }
        val unpinned = filterNot { it in state.pinnedFileUrls }
        clear()
        addAll(pinned + unpinned)
    }

    private data class FileLocation(
        val urls: MutableList<String>,
        val index: Int,
    )

    companion object {
        const val UNGROUPED_GROUP_ID: String = "editor-deck-ungrouped"
    }
}

/**
 * IntelliJ persistence service that owns the mutable settings object for one project.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "EditorDeckSettings",
    storages = [Storage("editor-deck.xml")]
)
class OpenEditorsSettingsService : PersistentStateComponent<OpenEditorsSettingsState> {
    private var state = OpenEditorsSettingsState()

    val model: OpenEditorsStateModel
        get() = OpenEditorsStateModel(state)

    override fun getState(): OpenEditorsSettingsState = state

    override fun loadState(state: OpenEditorsSettingsState) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): OpenEditorsSettingsService =
            project.getService(OpenEditorsSettingsService::class.java)
    }
}
