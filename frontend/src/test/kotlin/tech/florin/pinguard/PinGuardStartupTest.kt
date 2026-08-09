package tech.florin.pinguard

import com.intellij.openapi.actionSystem.ActionManager
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The path from `plugin.xml` to a wrapped action.
 *
 * Every other test in the suite builds its wrappers by hand, so if
 * [PinGuardCloseActions] were dropped from the module descriptor, or its install
 * stopped happening, only this would say so.
 */
internal class PinGuardStartupTest : RealEditorTestCase() {

    @Test
    fun `the startup activity is what puts the guards on the IDE's close actions`() {
        // Run explicitly rather than leaning on the fixture's own project open having
        // already done it, which would make this pass or fail on test class order.
        runBlocking { PinGuardCloseActions().execute(project) }

        val actions = ActionManager.getInstance()

        GUARDED.keys.forEach { id ->
            assertTrue(
                actions.getAction(id) is GuardedCloseAction,
                "'$id' is unguarded after the startup activity ran; the plugin would be installed and inert",
            )
        }
    }
}
