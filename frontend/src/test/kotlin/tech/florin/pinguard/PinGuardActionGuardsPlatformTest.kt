package tech.florin.pinguard

import com.intellij.diagnostic.LoadingState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.runInEdtAndWait
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Taking the guards off without the platform logging about it.
 *
 * Separate from [PinGuardActionGuardsTest], and holding a project open, for one
 * reason: `AnAction.setShortcutSet` only reaches the branch that logs once
 * [LoadingState.PROJECT_OPENED] has occurred, and a bare `@TestApplication` never
 * opens a project — so the same test written there passes whether or not the bug is
 * present. It cannot be left to run order either: `LoadingState` is a one-way latch
 * shared by the whole JVM, so a suite that happened to run a project-opening test
 * first would arm this one and a suite that did not would not.
 *
 * A bare [projectFixture] rather than [RealEditorTestCase], whose real
 * `FileEditorManagerImpl`, hand-built coroutine scope and long teardown exist to
 * make *editors* testable. Nothing here opens a file. What this needs from a project
 * is only that opening one flips the latch, and the assertion below says so out
 * loud rather than trusting it.
 */
@TestApplication
@TestFixtures
internal class PinGuardActionGuardsPlatformTest {

    private val projectFixture = projectFixture(openAfterCreation = true)

    private val actions: ActionManager get() = ActionManager.getInstance()

    private val installedByThePlugin: PinGuardActionGuards
        get() = ApplicationManager.getApplication().service()

    /**
     * The plugin is really loaded in the test sandbox, so opening a project runs
     * [PinGuardCloseActions] and leaves every close action already wrapped. A fresh
     * [PinGuardActionGuards] would then find wrappers, skip them all, and have
     * nothing recorded to restore — so the test would dispose an empty map and prove
     * nothing. Handing the platform's own actions back first is what gives this test
     * something to displace.
     */
    @BeforeEach
    fun openAProjectAndStartFromThePlatformsOwnActions() {
        // The fixture is lazy, and it is the *opening* that arms this test.
        projectFixture.get()
        runInEdtAndWait { installedByThePlugin.dispose() }
    }

    @AfterEach
    fun leaveTheGuardsInstalledAsTheyWereFound() {
        runInEdtAndWait { installedByThePlugin.install(actions) }
    }

    @Test
    fun `restoring an action does not log the platform's global-shortcut complaint`() {
        // `ActionManager.replaceAction` assigns the id's shortcut set to whatever it
        // registers, and `AnAction.setShortcutSet` logs a PluginException against
        // this plugin when the action it assigns to is registered under an id and
        // already carries a set of its own. Installing never tripped it, because a
        // fresh wrapper starts empty; restoring did — once per guarded action, on
        // every unload and every IDE shutdown, with
        // `PluginProblemReporterImpl is initialized during dispose` on top of the
        // first when the container was already going down.

        // The latch the platform checks before it logs at all. Asserted rather than
        // assumed: if opening a project ever stops flipping it, this test stops being
        // able to fail, and silence would read as the bug being fixed.
        assertTrue(
            LoadingState.PROJECT_OPENED.isOccurred,
            "no project is open, so AnAction.setShortcutSet cannot reach the branch this test is about",
        )

        lateinit var guards: PinGuardActionGuards
        runInEdtAndWait { guards = PinGuardActionGuards().apply { ensureInstalled() } }

        // Without this the test would also pass on an install that wrapped nothing,
        // leaving dispose() with an empty map and no shortcut set to assign.
        GUARDED.keys.forEach { id ->
            assertTrue(actions.getAction(id) is GuardedCloseAction, "'$id' was never wrapped, so nothing was proven")
        }

        val warnings = mutableListOf<String>()
        val capture = object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += "$category: $message"
                return false
            }
        }

        LoggedErrorProcessor.executeWith<Throwable>(capture) {
            runInEdtAndWait { guards.dispose() }
        }

        GUARDED.keys.forEach { id ->
            assertFalse(actions.getAction(id) is GuardedCloseAction, "'$id' was never restored, so nothing was proven")
        }
        assertTrue(
            warnings.none { "ShortcutSet" in it },
            "restoring logged the platform's global-shortcut complaint: ${warnings.filter { "ShortcutSet" in it }}",
        )
    }
}
