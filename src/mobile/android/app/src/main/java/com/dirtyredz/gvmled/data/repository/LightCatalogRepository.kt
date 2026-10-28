package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.light.connection.normalizeAddress
import com.dirtyredz.gvmled.data.local.CatalogResolution
import com.dirtyredz.gvmled.data.local.LightCatalogCodec
import com.dirtyredz.gvmled.data.model.KnownLight
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.model.LightGroup
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface CatalogResult {
    /** Saved durably and published. [groupId] is set for a created group. */
    data class Saved(val groupId: String? = null) : CatalogResult
    data class Rejected(val reason: String) : CatalogResult
}

/**
 * The known lights and named groups. Mutations are serialized; each writes the complete new snapshot
 * durably before publishing it, so observers never see a change that was not saved. Unreadable saved
 * data blocks mutations (only an explicit [resetUnreadable] replaces it). Forgetting lights is not
 * offered yet; deleting a group never touches its member lights.
 */
class LightCatalogRepository(
    private val store: LightCatalogStore,
    private val io: CoroutineDispatcher,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val mutableCatalog: MutableStateFlow<LightCatalog>
    private val mutableProblem: MutableStateFlow<String?>

    init {
        when (val resolved = LightCatalogCodec.resolve(store.read())) {
            is CatalogResolution.Ready -> {
                val saved = !resolved.needsWrite || store.write(resolved.catalog)
                mutableCatalog = MutableStateFlow(resolved.catalog)
                mutableProblem = MutableStateFlow(if (saved) null else "Saved lights could not be updated")
            }
            is CatalogResolution.Unreadable -> {
                mutableCatalog = MutableStateFlow(LightCatalog.EMPTY)
                mutableProblem = MutableStateFlow(resolved.reason)
            }
        }
    }

    val catalog: StateFlow<LightCatalog> = mutableCatalog.asStateFlow()

    /** Why saved data could not be used; null when the catalog is healthy. */
    val problem: StateFlow<String?> = mutableProblem.asStateFlow()

    /** Enrolls a discovered address under [name]. Not a compatibility claim: the session verifies it. */
    suspend fun addLight(address: String, name: String): CatalogResult = mutate { catalog ->
        val normalized = normalizeAddress(address)
        Change.Next(catalog.copy(lights = catalog.lights + KnownLight(normalized, name.trim())))
    }

    suspend fun rename(address: String, name: String): CatalogResult = mutate { catalog ->
        val normalized = normalizeAddress(address)
        if (catalog.light(normalized) == null) Change.Reject("That light is not saved")
        else Change.Next(catalog.copy(lights = catalog.lights.map { if (it.address == normalized) it.copy(name = name.trim()) else it }))
    }

    suspend fun createGroup(name: String, members: Collection<String>): CatalogResult {
        val id = newId()
        val result = mutate { catalog ->
            Change.Next(catalog.copy(groups = catalog.groups + LightGroup(id, name.trim(), cleanMembers(catalog, members))))
        }
        return if (result is CatalogResult.Saved) CatalogResult.Saved(id) else result
    }

    /** Changes the name and membership; the group keeps its ID. */
    suspend fun updateGroup(id: String, name: String, members: Collection<String>): CatalogResult = mutate { catalog ->
        if (catalog.group(id) == null) Change.Reject("That group no longer exists")
        else Change.Next(
            catalog.copy(
                groups = catalog.groups.map {
                    if (it.id == id) it.copy(name = name.trim(), members = cleanMembers(catalog, members)) else it
                },
            ),
        )
    }

    /** Removes only the group; its lights and every other group stay as they are. */
    suspend fun deleteGroup(id: String): CatalogResult = mutate { catalog ->
        if (catalog.group(id) == null) Change.Reject("That group no longer exists")
        else Change.Next(catalog.copy(groups = catalog.groups.filterNot { it.id == id }))
    }

    /** Deliberately replaces unreadable saved data with the default lights. */
    suspend fun resetUnreadable(): CatalogResult = mutex.withLock {
        if (mutableProblem.value == null) return CatalogResult.Rejected("Saved lights are readable")
        commit(LightCatalogCodec.seeded())
    }

    /** What a mutation proposes; every proposed catalog is then checked by [LightCatalog.problem]. */
    private sealed interface Change {
        data class Next(val catalog: LightCatalog) : Change
        data class Reject(val reason: String) : Change
    }

    private suspend fun mutate(transform: (LightCatalog) -> Change): CatalogResult = mutex.withLock {
        if (mutableProblem.value != null) return CatalogResult.Rejected("Saved lights could not be read, so changes are paused")
        when (val change = transform(mutableCatalog.value)) {
            is Change.Reject -> CatalogResult.Rejected(change.reason)
            is Change.Next -> commit(change.catalog)
        }
    }

    private suspend fun commit(next: LightCatalog): CatalogResult {
        next.problem()?.let { return CatalogResult.Rejected(it) }
        val written = withContext(io) { store.write(next) }
        if (!written) return CatalogResult.Rejected("The change could not be saved")
        mutableCatalog.value = next
        mutableProblem.value = null
        return CatalogResult.Saved()
    }

    /** Catalog order and deduplicated; an address no longer saved is kept so validation reports it. */
    private fun cleanMembers(catalog: LightCatalog, members: Collection<String>): List<String> {
        val chosen = members.map(::normalizeAddress).distinct()
        return catalog.addresses.filter { it in chosen } + chosen.filter { catalog.light(it) == null }
    }
}
