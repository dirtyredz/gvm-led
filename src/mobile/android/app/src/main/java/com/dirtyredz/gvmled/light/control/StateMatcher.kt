package com.dirtyredz.gvmled.light.control

import com.dirtyredz.gvmled.light.protocol.FullStateReport

/**
 * Compares a wanted state with a full state report. Power, mode and brightness always count; of the
 * rest only the fields the wanted mode (or scene) uses, so inactive values the light still reports
 * never block convergence. Only a [FullStateReport] can be compared: a short reply or a set echo has
 * no mode or colour fields and can never confirm anything.
 */
object StateMatcher {
    /** Owner-readable differences; empty when the report shows the wanted state. */
    fun mismatches(wanted: WantedState, report: FullStateReport): List<String> {
        val out = mutableListOf<String>()
        if ((report.power == 1) != wanted.power) out += "power"
        if (report.mode != wanted.mode.wire) out += "mode"
        if (report.brightness != wanted.brightness) out += "brightness"
        for (field in ModeCommandPlanner.required(wanted)) {
            if (reported(field, report) != field.read(wanted)) out += field.label
        }
        return out
    }

    fun matches(wanted: WantedState, report: FullStateReport): Boolean = mismatches(wanted, report).isEmpty()

    private fun reported(field: Field, report: FullStateReport): Int = when (field) {
        Field.Cct -> report.cct
        Field.Hue -> report.hue
        Field.Saturation -> report.saturation
        Field.Scene -> report.scene
        Field.EffectTime -> report.effectTime
    }
}
