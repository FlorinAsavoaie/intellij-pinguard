package tech.florin.pinguard

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * What the guard does when it cannot reach a decision — the part no fixture can
 * produce: a lookup or a prompt that throws.
 *
 * The decisions themselves belong to [PinnedCloseGateTest]; the guard against a
 * real pinned tab is [PinnedTabCloseGuardPlatformTest].
 */
class PinnedTabCloseGuardTest {

    private class FixedPrompt(private val answer: Boolean) : ConfirmationPrompt {
        override fun confirmClosingPinned(pinnedNames: List<String>): Boolean = answer
    }

    private object ExplodingPrompt : ConfirmationPrompt {
        override fun confirmClosingPinned(pinnedNames: List<String>): Boolean = error("no UI here")
    }

    private fun file(name: String): VirtualFile = LightVirtualFile(name, "")

    private val guarding = PinGuardState(enabled = true, confirmInsteadOfBlock = false)
    private val confirming = PinGuardState(enabled = true, confirmInsteadOfBlock = true)

    private fun guardWhoseLookup(
        config: PinGuardState = guarding,
        prompt: ConfirmationPrompt = FixedPrompt(answer = false),
        selector: PinnedFileSelector,
    ) = PinnedTabCloseGuard(
        selector = selector,
        gate = PinnedCloseGate(configProvider = { config }, prompt = prompt),
    )

    @Test
    fun `a broken pin lookup lets the tab close instead of trapping it`() {
        val guard = guardWhoseLookup(
            selector = PinnedFileSelector { error("editor windows went away mid-close") },
        )

        assertTrue(
            guard.canCloseFile(file("Main.kt")),
            "the platform does not guard this call: a throw here aborts the user's close",
        )
    }

    @Test
    fun `even cancellation does not escape into the platform's close path`() {
        // AlreadyDisposedException has changed supertype between platform releases,
        // so no version-portable rethrow rule can tell it from a real cancellation.
        val guard = guardWhoseLookup(
            selector = PinnedFileSelector { throw ProcessCanceledException() },
        )

        assertTrue(guard.canCloseFile(file("Main.kt")))
    }

    @Test
    fun `a broken prompt also fails open`() {
        val pinned = file("Main.kt")
        val guard = guardWhoseLookup(
            config = confirming,
            prompt = ExplodingPrompt,
            selector = { targets ->
                targets.filter { it.file == pinned }.map { PinnedFile(it.file, listOf("proj")) }
            },
        )

        assertTrue(guard.canCloseFile(pinned))
    }

    @Test
    fun `a VirtualMachineError is not swallowed, because nothing can be decided after one`() {
        val guard = guardWhoseLookup(
            selector = PinnedFileSelector { throw OutOfMemoryError("test") },
        )

        val thrown = runCatching { guard.canCloseFile(file("Main.kt")) }.exceptionOrNull()

        assertTrue(thrown is OutOfMemoryError, "swallowing this would hide a dying JVM behind a working close")
    }
}
