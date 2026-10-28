package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.connection.normalizeAddress
import com.dirtyredz.gvmled.data.local.CatalogResolution
import com.dirtyredz.gvmled.data.local.LightCatalogCodec
import com.dirtyredz.gvmled.data.model.KnownLight
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.model.LightGroup
import java.util.Locale
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
        val cleaned = name.trim()
        when {
            catalog.light(normalized) != null -> reject("That light is already added")
            !LightCatalog.isValidName(cleaned) -> reject(NAME_RULE)
            else -> catalog.copy(lights = catalog.lights + KnownLight(normalized, cleaned))
        }
    }

    suspend fun rename(address: String, name: String): CatalogResult = mutate { catalog ->
        val normalized = normalizeAddress(address)
        val cleaned = name.trim()
        when {
            catalog.light(normalized) == null -> reject("That light is not saved")
            !LightCatalog.isValidName(cleaned) -> reject(NAME_RULE)
            else -> catalog.copy(lights = catalog.lights.map { if (it.address == normalized) it.copy(name = cleaned) else it })
        }
    }

    suspend fun createGroup(name: String, members: Collection<String>): CatalogResult {
        val id = newId()
        val result = mutate { catalog ->
            groupProblem(catalog, null, name, members)?.let(::reject)
                ?: catalog.copy(groups = catalog.groups + LightGroup(id, name.trim(), cleanMembers(catalog, members)))
        }
        return if (result is CatalogResult.Saved) CatalogResult.Saved(id) else result
    }

    /** Changes the name and membership; the group keeps its ID. */
    suspend fun updateGroup(id: String, name: String, members: Collection<String>): CatalogResult = mutate { catalog ->
        when {
            catalog.group(id) == null -> reject("That group no longer exists")
            else -> groupProblem(catalog, id, name, members)?.let(::reject) ?: catalog.copy(
                groups = catalog.groups.map {
                    if (it.id == id) it.copy(name = name.trim(), members = cleanMembers(catalog, members)) else it
                },
            )
        }
    }

    /** Removes only the group; its lights and every other group stay as they are. */
    suspend fun deleteGroup(id: String): CatalogResult = mutate { catalog ->
        if (catalog.group(id) == null) reject("That group no longer exists")
        else catalog.copy(groups = catalog.groups.filterNot { it.id == id })
    }

    /** Deliberately replaces unreadable saved data with the default lights. */
    suspend fun resetUnreadable(): CatalogResult = mutex.withLock {
        if (mutableProblem.value == null) return CatalogResult.Rejected("Saved lights are readable")
        commit(LightCatalogCodec.seeded())
    }

    private class Rejection(val reason: String)

    private fun reject(reason: String) = Rejection(reason)

    /** [transform] returns the next catalog or a [Rejection]. */
    private suspend fun mutate(transform: (LightCatalog) -> Any): CatalogResult = mutex.withLock {
        if (mutableProblem.value != null) return CatalogResult.Rejected("Saved lights could not be read, so changes are paused")
        when (val next = transform(mutableCatalog.value)) {
            is Rejection -> CatalogResult.Rejected(next.reason)
            is LightCatalog -> commit(next)
            else -> error("Unexpected transform result")
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

    private fun cleanMembers(catalog: LightCatalog, members: Collection<String>): List<String> {
        val chosen = members.map(::normalizeAddress).toSet()
        return catalog.addresses.filter { it in chosen } // catalog order, deduplicated
    }

    private fun groupProblem(catalog: LightCatalog, editingId: String?, name: String, members: Collection<String>): String? {
        val cleaned = name.trim()
        val chosen = members.map(::normalizeAddress).toSet()
        return when {
            !LightCatalog.isValidName(cleaned) -> NAME_RULE
            catalog.groups.any { it.id != editingId && it.name.lowercase(Locale.ROOT) == cleaned.lowercase(Locale.ROOT) } ->
                "Another group is already called \"$cleaned\""
            chosen.isEmpty() -> "Choose at least one light"
            chosen.any { catalog.light(it) == null } -> "A chosen light is no longer saved"
            else -> null
        }
    }

    private companion object {
        const val NAME_RULE = "Use a name of 1 to ${LightCatalog.MAX_NAME_LENGTH} characters"
    }
}
