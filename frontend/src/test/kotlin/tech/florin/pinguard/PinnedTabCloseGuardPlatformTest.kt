package tech.florin.pinguard

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The extension point class as the platform builds it: through the no-argument
 * constructor, against real pinned tabs in a real project.
 *
 * That constructor wires up [PlatformPinnedFiles], [PinGuardSettings] and
 * [MessagesConfirmationPrompt], none of which a fake selector exercises — so what
 * [PinnedTabCloseGuardTest] proves about the *decision* it also has to assume about
 * the *lookup*.
 */
internal class PinnedTabCloseGuardPlatformTest : RealEditorTestCase() {

    private val guard = PinnedTabCloseGuard()

    @Test
    fun `a really pinned tab cannot be closed`() {
        val main = open("Main.kt")
        pin(main)

        assertFalse(
            runInEdtAndGetValue { guard.canCloseFile(main) },
            "the guard is reading pins out of the running editor, not out of a fake",
        )
    }

    @Test
    fun `an unpinned tab closes normally`() {
        val main = open("Main.kt")
        val other = open("Other.kt")
        pin(main)

        assertTrue(runInEdtAndGetValue { guard.canCloseFile(other) })
    }

    @Test
    fun `each file is judged on its own pin`() {
        // Not a "Close All", which never reaches this extension point: each call is
        // its own close, and one pinned tab must not colour the answer for the next
        // file asked about.
        val main = open("Main.kt")
        val other = open("Other.kt")
        pin(main)

        assertTrue(runInEdtAndGetValue { guard.canCloseFile(other) })
        assertFalse(runInEdtAndGetValue { guard.canCloseFile(main) })
    }

    @Test
    fun `unpinning a tab lets it close again`() {
        val main = open("Main.kt")
        pin(main)
        assertFalse(runInEdtAndGetValue { guard.canCloseFile(main) })

        inEdt { mainWindow().setFilePinned(main, false) }

        assertTrue(runInEdtAndGetValue { guard.canCloseFile(main) })
    }

    @Test
    fun `a file that is not open anywhere is not treated as pinned`() {
        // EditorWindow.isFilePinned throws for a file the window does not hold,
        // which would otherwise be reported as "cannot decide".
        val neverOpened = file("Untouched.kt")
        pin(open("Main.kt"))

        assertTrue(runInEdtAndGetValue { guard.canCloseFile(neverOpened) })
    }

    @Test
    fun `PinGuard is registered on the extension point the platform actually reads`() {
        // Everything else in this file builds a guard and calls it directly, which
        // says nothing about whether the IDE ever asks it. The registration in
        // tech.florin.pinguard.frontend.xml could be misspelled, or the file renamed
        // so the whole module fails to load, and every other test here would still
        // pass.
        val registered = ExtensionPointName<VirtualFilePreCloseCheck>("com.intellij.virtualFilePreCloseCheck")
            .extensionList

        assertTrue(
            registered.any { it is PinnedTabCloseGuard },
            "PinGuard is not on the extension point; the platform will never consult it. Registered: " +
                registered.map { it.javaClass.name },
        )
    }

    @Test
    fun `the platform's own checked close path honours the veto`() {
        // closeFileWithChecks is what consults the extension point, so this is the
        // one test that goes through the platform rather than around it.
        val pinned = open("Pinned.kt")
        pin(pinned)

        val closed = runInEdtAndGetValue { manager.closeFileWithChecks(pinned, mainWindow()) }
        drainTheEventQueue()

        assertFalse(closed, "closeFileWithChecks closed a pinned tab; the veto never reached it")
        assertTrue(pinned in openFiles(), "the pinned tab is gone")
    }

    @Test
    fun `the platform's own checked close path still closes an unpinned tab`() {
        val loose = open("Loose.kt")

        val closed = runInEdtAndGetValue { manager.closeFileWithChecks(loose, mainWindow()) }
        drainTheEventQueue()

        assertTrue(closed, "an unpinned tab must close normally; a guard that blocks everything is worse than none")
        assertFalse(loose in openFiles())
    }

    @Test
    fun `a disabled guard stays out of the way of a real pin`() {
        val main = open("Main.kt")
        pin(main)

        withConfig(PinGuardState(enabled = false, confirmInsteadOfBlock = false)) {
            assertTrue(runInEdtAndGetValue { guard.canCloseFile(main) })
        }
    }
}
