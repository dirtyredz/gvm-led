package com.dirtyredz.gvmled.ui.navigation

import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dirtyredz.gvmled.AppContainer
import com.dirtyredz.gvmled.feature.lights.LightsActions
import com.dirtyredz.gvmled.feature.lights.LightsScreen
import com.dirtyredz.gvmled.feature.lights.LightsViewModel
import com.dirtyredz.gvmled.feature.settings.SettingsLink
import com.dirtyredz.gvmled.feature.settings.SettingsScreen
import com.dirtyredz.gvmled.feature.settings.SettingsViewModel
import com.dirtyredz.gvmled.feature.settings.groups.GroupEditor
import com.dirtyredz.gvmled.feature.settings.groups.GroupEditorActions
import com.dirtyredz.gvmled.feature.settings.groups.GroupsScreen
import com.dirtyredz.gvmled.feature.settings.groups.GroupsViewModel
import com.dirtyredz.gvmled.feature.settings.lights.LightManagementActions
import com.dirtyredz.gvmled.feature.settings.lights.LightManagementScreen
import com.dirtyredz.gvmled.feature.settings.lights.LightManagementViewModel
import com.dirtyredz.gvmled.ui.components.RadioActions

/** The app's screens. Internal navigation never touches BLE ownership (that follows the activity). */
enum class Destination { Lights, Settings, ManageLights, Groups, GroupEditor }

/** A tiny saved back stack (survives rotation and process death) instead of a navigation library. */
private class BackStack(initial: List<Destination>) {
    var entries by mutableStateOf(initial)
    val current: Destination get() = entries.last()
    fun push(destination: Destination) { entries = entries + destination }
    fun pop() { if (entries.size > 1) entries = entries.dropLast(1) }

    companion object {
        val Saver = listSaver<BackStack, String>(
            save = { stack -> stack.entries.map { it.name } },
            restore = { names ->
                BackStack(names.mapNotNull { name -> Destination.entries.firstOrNull { it.name == name } }.ifEmpty { listOf(Destination.Lights) })
            },
        )
    }
}

/** Activity-scoped ViewModel built from [create]; the activity outlives every destination. */
@Composable
private inline fun <reified T : ViewModel> appViewModel(noinline create: () -> T): T =
    viewModel(factory = viewModelFactory { initializer { create() } })

/** Composition root: destinations and their ViewModel factories, all fed from [container]. */
@Composable
fun GvmApp(container: AppContainer, radioActions: RadioActions) {
    val stack = rememberSaveable(saver = BackStack.Saver) { BackStack(listOf(Destination.Lights)) }
    BackHandler(enabled = stack.entries.size > 1) { stack.pop() }
    when (stack.current) {
        Destination.Lights -> {
            val model = appViewModel { LightsViewModel(container.lights, container.catalog.catalog, container.radio.state, container.control) }
            val state by model.state.collectAsStateWithLifecycle()
            LightsScreen(
                state,
                radioActions,
                LightsActions(select = model::select, apply = model::apply, resetSettings = model::resetSettings),
                onOpenSettings = { stack.push(Destination.Settings) },
            )
        }
        Destination.Settings -> {
            val model = appViewModel {
                SettingsViewModel(container.updates, Build.VERSION.SDK_INT, container.releaseContract?.developmentPackageName)
            }
            val state by model.state.collectAsStateWithLifecycle()
            val links = listOf(
                SettingsLink("Lights", "Find, add and rename lights") { stack.push(Destination.ManageLights) },
                SettingsLink("Groups", "Create, edit and delete named groups") { stack.push(Destination.Groups) },
            )
            SettingsScreen(state, model::perform, links, onBack = stack::pop)
        }
        Destination.ManageLights -> {
            val model = appViewModel {
                LightManagementViewModel(container.catalog, container.lights, container.discovery, container.radio.state, SystemClock::elapsedRealtime)
            }
            val state by model.state.collectAsStateWithLifecycle()
            val actions = LightManagementActions(model::find, model::add, model::rename, model::resetSaved, model::dismissMessage)
            LightManagementScreen(state, actions, radioActions, onBack = stack::pop)
        }
        Destination.Groups, Destination.GroupEditor -> {
            val model = appViewModel { GroupsViewModel(container.catalog, container.lights) }
            val state by model.state.collectAsStateWithLifecycle()
            val editor = state.editor
            if (stack.current == Destination.Groups) {
                GroupsScreen(
                    state,
                    onCreate = { model.startCreate(); stack.push(Destination.GroupEditor) },
                    onEdit = { model.startEdit(it); stack.push(Destination.GroupEditor) },
                    onBack = stack::pop,
                )
            } else if (editor != null) {
                val close = { model.cancel(); stack.pop() }
                BackHandler { close() }
                GroupEditor(
                    editor,
                    GroupEditorActions(
                        setName = model::setName,
                        toggle = model::toggle,
                        save = { model.save(onSaved = stack::pop) },
                        delete = { id -> model.delete(id, onDeleted = stack::pop) },
                        cancel = close,
                    ),
                )
            } else if (!model.isEditing) {
                // The draft did not survive (process restart): return to the list.
                LaunchedEffect(Unit) { stack.pop() }
            }
        }
    }
}
