package com.dirtyredz.gvmled.feature.settings.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtyredz.gvmled.connection.LinkStatus
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.repository.CatalogResult
import com.dirtyredz.gvmled.data.repository.LightCatalogRepository
import com.dirtyredz.gvmled.data.repository.LightEntry
import com.dirtyredz.gvmled.data.repository.LightsRepository
import com.dirtyredz.gvmled.feature.lights.addressSuffix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupRow(val id: String, val name: String, val members: String)

/** One known light offered in the editor; offline lights can be chosen too. */
data class MemberOption(val address: String, val label: String, val availability: String, val selected: Boolean)

/** An open create/edit form. [groupId] is null while creating. */
data class GroupDraft(val groupId: String?, val name: String, val members: Set<String>, val error: String? = null)

data class GroupEditorState(
    val groupId: String?,
    val name: String,
    val options: List<MemberOption>,
    val error: String?,
)

data class GroupsUiState(
    val groups: List<GroupRow> = emptyList(),
    val editor: GroupEditorState? = null,
    val canCreate: Boolean = false,
)

/**
 * Settings > Groups: list, create, edit and delete named groups. The form draft lives here so it
 * survives rotation; validation is the catalog repository's (name rules, duplicates, membership).
 */
class GroupsViewModel(
    private val catalog: LightCatalogRepository,
    lights: LightsRepository,
) : ViewModel() {
    private val draft = MutableStateFlow<GroupDraft?>(null)

    val state: StateFlow<GroupsUiState> = combine(catalog.catalog, lights.lights, draft) { known, entries, open ->
        groupsState(known, entries, open)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // Synchronous first value: an open draft must be visible before the first emission.
        groupsState(catalog.catalog.value, emptyList(), draft.value),
    )

    /** True while a create/edit form is open (read directly, not through [state]). */
    val isEditing: Boolean get() = draft.value != null

    fun startCreate() {
        draft.value = GroupDraft(groupId = null, name = "", members = emptySet())
    }

    fun startEdit(id: String) {
        val group = catalog.catalog.value.group(id) ?: return
        draft.value = GroupDraft(id, group.name, group.members.toSet())
    }

    fun setName(name: String) = draft.update { it?.copy(name = name.take(LightCatalog.MAX_NAME_LENGTH), error = null) }

    fun toggle(address: String) = draft.update {
        it?.copy(members = if (address in it.members) it.members - address else it.members + address, error = null)
    }

    fun cancel() {
        draft.value = null
    }

    /** Saves the open draft; [onSaved] runs only after the catalog durably saved it. */
    fun save(onSaved: () -> Unit) {
        val current = draft.value ?: return
        viewModelScope.launch {
            val result = current.groupId?.let { catalog.updateGroup(it, current.name, current.members) }
                ?: catalog.createGroup(current.name, current.members)
            when (result) {
                is CatalogResult.Saved -> {
                    draft.value = null
                    onSaved()
                }
                is CatalogResult.Rejected -> draft.update { it?.copy(error = result.reason) }
            }
        }
    }

    /** Deletes the group only; its lights and other groups are untouched. */
    fun delete(id: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            when (val result = catalog.deleteGroup(id)) {
                is CatalogResult.Saved -> {
                    draft.value = null
                    onDeleted()
                }
                is CatalogResult.Rejected -> draft.update { it?.copy(error = result.reason) }
            }
        }
    }
}

internal fun groupsState(catalog: LightCatalog, entries: List<LightEntry>, open: GroupDraft?): GroupsUiState {
    val status = entries.associate { it.connection.address to it.connection.status }
    return GroupsUiState(
        groups = catalog.groups.map { group ->
            GroupRow(group.id, group.name, group.members.mapNotNull { catalog.light(it)?.name }.joinToString(", "))
        },
        editor = open?.let { draft ->
            GroupEditorState(
                groupId = draft.groupId,
                name = draft.name,
                options = catalog.lights.map { light ->
                    MemberOption(
                        address = light.address,
                        label = "${light.name} …${addressSuffix(light.address)}",
                        availability = if (status[light.address] == LinkStatus.Live) "Live" else "Not connected",
                        selected = light.address in draft.members,
                    )
                },
                error = draft.error,
            )
        },
        canCreate = catalog.lights.isNotEmpty(),
    )
}
