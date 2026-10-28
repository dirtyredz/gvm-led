package com.dirtyredz.gvmled.light.control

import com.dirtyredz.gvmled.light.protocol.LegacyCommand
import com.dirtyredz.gvmled.light.protocol.SceneCatalog
import com.dirtyredz.gvmled.light.protocol.SetCommand

/** A complete transaction for one light, or why the wanted state cannot be sent as it stands. */
sealed interface CommandPlan {
    data class Ready(val commands: List<SetCommand>) : CommandPlan
    data class Blocked(val reason: String) : CommandPlan
}

/**
 * Plans the complete, ordered set commands for a wanted state. Mode (06) is always first, so the
 * light can never keep an earlier mode while it receives the new values (the official app's colour
 * bug: on resume it re-sends only intensity, and the light stays in its previous mode). Then every
 * value the mode uses, brightness, and power (00) last: the 2026-10-26 proof showed values sent
 * while off are stored and a mode change never powers the light on, so the settings are in place
 * before it lights up.
 */
object ModeCommandPlanner {
    fun plan(state: WantedState, range: CctRange): CommandPlan {
        problem(state, range)?.let { return CommandPlan.Blocked(it) }
        val values = requiredInOrder(state).map { field -> SetCommand(field.command, checkNotNull(field.read(state))) }
        val commands = listOf(SetCommand(LegacyCommand.MODE, state.mode.wire)) + values +
            SetCommand(LegacyCommand.BRIGHTNESS, state.brightness) +
            SetCommand(LegacyCommand.POWER, if (state.power) 1 else 0)
        return CommandPlan.Ready(commands)
    }

    /**
     * Why [state] is not a complete, legal intent, or null. With [range] null (the light has not
     * reported its range yet) a present CCT is accepted as stored; [plan] checks it against the
     * current range before anything is sent.
     */
    fun problem(state: WantedState, range: CctRange?): String? {
        if (state.brightness !in 0..100) return "brightness must be 0-100 %"
        val scene = state.scene
        if (state.mode == LightMode.Scene && scene != null && !SceneCatalog.isSupported(scene)) {
            return "scene $scene is not supported"
        }
        for (field in requiredInOrder(state)) {
            val value = field.read(state) ?: return "needs a value for ${field.label}"
            if (!field.isLegal(value)) return "${field.label} is out of range"
        }
        val cct = state.cct
        if (Field.Cct in required(state) && cct != null && range != null && cct !in range) {
            return "colour temperature ${cct * 100} K is outside its range $range"
        }
        return null
    }

    /** The mode-specific fields the light uses in [state]'s mode (power, mode and brightness always apply). */
    fun required(state: WantedState): Set<Field> = requiredInOrder(state).toSet()

    /** Wire order after the mode command: scene first, then its CCT, colour and effect time. */
    private fun requiredInOrder(state: WantedState): List<Field> = when (state.mode) {
        LightMode.Cct -> listOf(Field.Cct)
        LightMode.Hsi -> listOf(Field.Hue, Field.Saturation)
        LightMode.Scene -> {
            val scene = state.scene
            if (scene == null || !SceneCatalog.isSupported(scene)) listOf(Field.Scene)
            else SceneCatalog.fields(scene).let { fields ->
                listOfNotNull(
                    Field.Scene,
                    Field.Cct.takeIf { fields.cct },
                    Field.Hue.takeIf { fields.colour },
                    Field.Saturation.takeIf { fields.colour },
                    Field.EffectTime.takeIf { fields.effectTime },
                )
            }
        }
    }
}

/** The mode-specific values of a [WantedState], with their set command and legal wire range. */
enum class Field(val label: String, val command: Int, val limits: IntRange) {
    Cct("colour temperature", LegacyCommand.CCT, 1..255),
    Hue("hue", LegacyCommand.HUE, 0..71),
    Saturation("saturation", LegacyCommand.SATURATION, 0..100),
    Scene("scene", LegacyCommand.SCENE, SceneCatalog.IDS),
    EffectTime("effect time", LegacyCommand.EFFECT_TIME, SceneCatalog.EFFECT_TIMES);

    fun isLegal(value: Int) = value in limits

    /** The light may report hue 72 (360 degrees), which is the same editable colour as 0. */
    fun normalize(value: Int): Int = if (this == Hue && value == 72) 0 else value

    fun read(state: WantedState): Int? = when (this) {
        Cct -> state.cct
        Hue -> state.hue
        Saturation -> state.saturation
        Scene -> state.scene
        EffectTime -> state.effectTime
    }
}
