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
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
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
     * Leaves the guards on rather than taking them off first.
     *
     * `PinGuardCloseActions` is launched as its own coroutine when a project opens
     * and is joined by nothing — `allActivitiesPassedFuture` completes once the
     * activities are launched, not once they have run. Any test that disposes the
     * service and then works over the unwrapped actions is therefore racing an
     * install it cannot see coming. Leaving `installed` true makes that install the
     * no-op it already is for a second project.
     */
    @BeforeEach
    fun openAProjectAndPutTheGuardsOn() {
        // The fixture is lazy, and it is the *opening* that arms this test.
        projectFixture.get()
        runInEdtAndWait { installedByThePlugin.ensureInstalled() }
    }

    @AfterEach
    fun leaveTheGuardsInstalledAsTheyWereFound() {
        runInEdtAndWait { installedByThePlugin.ensureInstalled() }
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

        // Held by identity, because that is what the restore is asked about below.
        // Whether the id carries *a* wrapper afterwards cannot answer it: disposing
        // arms `PinGuardCloseActions` again, and a later project opening puts a fresh
        // wrapper on the same id without the restore having failed.
        val installed = GUARDED.keys.associateWith { actions.getAction(it) }
        installed.forEach { (id, action) ->
            assertTrue(action is GuardedCloseAction, "'$id' was never wrapped, so nothing was proven")
        }

        val warnings = mutableListOf<String>()
        val capture = object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += "$category: $message"
                return false
            }
        }

        LoggedErrorProcessor.executeWith<Throwable>(capture) {
            runInEdtAndWait { installedByThePlugin.dispose() }
        }

        GUARDED.keys.forEach { id ->
            assertNotSame(
                installed[id],
                actions.getAction(id),
                "'$id' still carries the wrapper this test found, so nothing was restored",
            )
        }
        assertTrue(
            warnings.none { "ShortcutSet" in it },
            "restoring logged the platform's global-shortcut complaint: ${warnings.filter { "ShortcutSet" in it }}",
        )
    }
}
