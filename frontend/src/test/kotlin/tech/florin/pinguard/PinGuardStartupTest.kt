package tech.florin.pinguard

import com.intellij.openapi.actionSystem.ActionManager
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The path from `plugin.xml` to a wrapped action.
 *
 * [PinGuardCloseActions] is a `postStartupActivity`, and nothing else in the
 * suite runs it: every other test builds its wrappers by hand. If the activity
 * were dropped from the module descriptor, or its install stopped happening, the
 * plugin would ship doing nothing at all and only this would say so.
 */
internal class PinGuardStartupTest : RealEditorTestCase() {

    @Test
    fun `the startup activity is what puts the guards on the IDE's close actions`() {
        // Run explicitly rather than leaning on the fixture's own project open
        // having already done it: that would make this pass or fail on which other
        // test class happened to run first, and the install is idempotent.
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
