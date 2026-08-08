package tech.florin.pinguard

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.runInEdtAndWait
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Settings | Editor | PinGuard.
 *
 * `BoundConfigurable` supplies `isModified`, `apply` and `reset` for free, but
 * only over bindings that actually round-trip. Each checkbox here reads and
 * writes the whole config (`settings.config = settings.config.copy(...)`), so
 * the two of them applying in sequence is a real thing to check rather than an
 * obvious one — as is the confirm box being disabled while the guard is off,
 * since a disabled cell still applies its value.
 */
@TestApplication
internal class PinGuardConfigurableTest {

    private val settings get() = PinGuardSettings.getInstance()

    @AfterEach
    fun resetSettings() {
        settings.config = PinGuardState()
    }

    /** Builds the panel and hands it to [body], disposing it afterwards. */
    private fun onThePanel(body: (PinGuardConfigurable) -> Unit) {
        val configurable = PinGuardConfigurable()
        try {
            runInEdtAndWait {
                configurable.createComponent()
                body(configurable)
            }
        } finally {
            runInEdtAndWait { configurable.disposeUIResources() }
        }
    }

    @Test
    fun `the panel opens showing what is stored`() {
        settings.config = PinGuardState(enabled = false, confirmInsteadOfBlock = true)

        onThePanel { configurable ->
            configurable.reset()

            assertFalse(configurable.isModified, "a freshly reset panel cannot already differ from the settings")
        }
    }

    @Test
    fun `turning the guard off is applied to the settings`() {
        onThePanel { configurable ->
            configurable.reset()
            enabledCheckBox(configurable).isSelected = false

            assertTrue(configurable.isModified, "the panel does not notice the change it is about to apply")
            configurable.apply()
        }

        assertFalse(settings.config.enabled)
    }

    @Test
    fun `both checkboxes survive one apply, despite each rewriting the whole config`() {
        // The bindings are read-modify-write over the same object, so an apply that
        // ordered them badly would drop one of the two.
        onThePanel { configurable ->
            configurable.reset()
            enabledCheckBox(configurable).isSelected = false
            confirmCheckBox(configurable).isSelected = true
            configurable.apply()
        }

        assertEquals(PinGuardState(enabled = false, confirmInsteadOfBlock = true), settings.config)
    }

    @Test
    fun `reset discards an unapplied change rather than leaking it`() {
        onThePanel { configurable ->
            configurable.reset()
            enabledCheckBox(configurable).isSelected = false

            configurable.reset()

            assertFalse(configurable.isModified)
        }

        assertEquals(PinGuardState(), settings.config, "cancelling the dialog must not have written anything")
    }

    @Test
    fun `the confirmation box is disabled while the guard itself is off`() {
        onThePanel { configurable ->
            configurable.reset()
            val confirm = confirmCheckBox(configurable)

            enabledCheckBox(configurable).isSelected = false
            assertFalse(confirm.isEnabled, "offering a confirmation setting for a guard that never runs")

            enabledCheckBox(configurable).isSelected = true
            assertTrue(confirm.isEnabled)
        }
    }

    @Test
    fun `the panel is built from the bundle rather than from hardcoded english`() {
        onThePanel { configurable ->
            assertEquals(PinGuardBundle.message("settings.enabled"), enabledCheckBox(configurable).text)
            assertEquals(PinGuardBundle.message("settings.confirm"), confirmCheckBox(configurable).text)
        }
        assertEquals(PinGuardBundle.message("settings.title"), PinGuardConfigurable().displayName)
    }

    private fun enabledCheckBox(configurable: PinGuardConfigurable) =
        checkBoxes(configurable).first { it.text == PinGuardBundle.message("settings.enabled") }

    private fun confirmCheckBox(configurable: PinGuardConfigurable) =
        checkBoxes(configurable).first { it.text == PinGuardBundle.message("settings.confirm") }

    private fun checkBoxes(configurable: PinGuardConfigurable): List<javax.swing.JCheckBox> {
        val root = requireNotNull(configurable.createComponent()) { "the configurable built no panel" }
        return buildList { collectCheckBoxes(root, this) }
    }

    private fun collectCheckBoxes(component: java.awt.Component, into: MutableList<javax.swing.JCheckBox>) {
        if (component is javax.swing.JCheckBox) into += component
        if (component is java.awt.Container) component.components.forEach { collectCheckBoxes(it, into) }
    }
}
