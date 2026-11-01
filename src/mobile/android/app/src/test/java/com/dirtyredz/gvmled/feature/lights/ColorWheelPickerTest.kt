package com.dirtyredz.gvmled.feature.lights

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.dirtyredz.gvmled.light.control.Shared
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Drives the real picker through recomposition, which the pure math tests cannot (callback freshness). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ColorWheelPickerTest {
    @get:Rule val rule = createComposeRule()

    private val wheel get() = rule.onNodeWithContentDescription("Colour wheel: angle sets hue, distance from centre sets saturation")

    @Test fun replacementCommitCallbackReceivesNextGesture() {
        val generation = mutableStateOf(0)
        val received = mutableListOf<Int>()
        rule.setContent {
            val current = generation.value
            MaterialTheme { ColorWheelPicker(Shared.Same(0), Shared.Same(100)) { _, _ -> received += current } }
        }
        wheel.performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf(0), received)
        rule.runOnIdle { generation.value = 1 }
        wheel.performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf(0, 1), received)
    }

    @Test fun secondDragShowsPreviewBeforeRelease() {
        val input = mutableStateOf(HueSat(0, 100))
        var commits = 0
        rule.setContent {
            MaterialTheme {
                ColorWheelPicker(Shared.Same(input.value.hue), Shared.Same(input.value.saturation)) { h, s ->
                    commits++
                    input.value = HueSat(h, s)
                }
            }
        }
        wheel.performTouchInput {
            down(Offset(width * .75f, height * .5f))
            moveTo(Offset(width * .5f, height * .75f), 100)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Hue 90°  Saturation 58 %").assertExists()
        assertEquals(0, commits)
        wheel.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, commits)
        assertEquals(HueSat(18, 58), input.value)

        wheel.performTouchInput {
            down(Offset(width * .5f, height * .75f))
            moveTo(Offset(width * .25f, height * .5f), 100)
        }
        rule.waitForIdle()
        assertEquals(1, commits)
        // Read the readout while the finger is still down, before release publishes the new input.
        val held = rule.onNode(hasText("Hue ", substring = true)).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        wheel.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(2, commits)
        assertEquals(HueSat(36, 58), input.value)
        assertEquals("Hue 180°  Saturation 58 %", held)
    }
}
