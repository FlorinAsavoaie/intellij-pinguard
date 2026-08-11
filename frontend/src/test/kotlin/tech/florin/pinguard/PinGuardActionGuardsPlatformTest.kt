package tech.florin.pinguard

import com.intellij.diagnostic.LoadingState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.startup.StartupManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.runInEdtAndWait
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Taking the guards off without the platform logging about it.
 *
 * Separate from [PinGuardActionGuardsTest], and holding a project open, because
 * `AnAction.setShortcutSet` only reaches the branch that logs once
 * [LoadingState.PROJECT_OPENED] has occurred — under a bare `@TestApplication` the
 * same test passes whether or not the bug is present. Run order cannot be relied on
 * either: `LoadingState` is a one-way latch shared by the whole JVM.
 *
 * A bare [projectFixture] rather than [RealEditorTestCase], since nothing here
 * opens a file; all this needs from a project is that opening one flips the latch.
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
     * [PinGuardActionGuards] would then skip them all and have nothing recorded to
     * restore, so the test would dispose an empty map and prove nothing.
     */
    @BeforeEach
    fun openAProjectAndStartFromThePlatformsOwnActions() {
        // The fixture is lazy, and it is the *opening* that arms this test.
        val project = projectFixture.get()

        // Waited for rather than assumed: the fixture returns once the project is
        // open, leaving `PinGuardCloseActions` to run on the project's own scope
        // afterwards. An install landing after the dispose below puts every wrapper
        // back, and the guards the test then builds skip all four and record nothing
        // to restore — which reads as a restore that did not happen.
        runBlocking {
            withTimeoutOrNull(30.seconds) { StartupManager.getInstance(project).allActivitiesPassedFuture.join() }
                ?: error("the project's startup activities never finished, so the guards below would race them")
        }

        runInEdtAndWait { installedByThePlugin.dispose() }
    }

    @AfterEach
    fun leaveTheGuardsInstalledAsTheyWereFound() {
        runInEdtAndWait { installedByThePlugin.install(actions) }
    }

    @Test
    fun `restoring an action does not log the platform's global-shortcut complaint`() {
        // The latch the platform checks before it logs at all. Asserted rather than
        // assumed: if opening a project ever stops flipping it, this test stops being
        // able to fail, and silence would read as the bug being fixed.
        assertTrue(
            LoadingState.PROJECT_OPENED.isOccurred,
            "no project is open, so AnAction.setShortcutSet cannot reach the branch this test is about",
        )

        // What the setup's dispose was for, checked before it is relied on: `guard`
        // skips an action that is already wrapped, and a skipped action never enters
        // the map the restore walks.
        GUARDED.keys.forEach { id ->
            assertFalse(
                actions.getAction(id) is GuardedCloseAction,
                "precondition: '$id' was still wrapped, so the install below recorded nothing to restore",
            )
        }

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
