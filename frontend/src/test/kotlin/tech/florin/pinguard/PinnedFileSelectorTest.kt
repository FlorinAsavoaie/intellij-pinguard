package tech.florin.pinguard

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The step between a pin lookup and the gate: `PinnedCloseGate.canClose(pinned)`.
 *
 * Both callers of the gate go through this extension, so it is the one place that
 * decides which file names a user sees when a close is refused.
 *
 * The other half of it — merging `pinnedIn` across files — is deliberately not
 * asserted here: it feeds nothing but an INFO line, and observing it would mean
 * asserting against a log format. [PlatformPinnedFilesTest] covers where those
 * labels come from in the first place.
 */
internal class PinnedFileSelectorTest {

    private class RecordingPrompt(private val answer: Boolean) : ConfirmationPrompt {
        var invocations = 0
            private set
        var lastNames: List<String>? = null
            private set

        override fun confirmClosingPinned(pinnedNames: List<String>): Boolean {
            invocations++
            lastNames = pinnedNames
            return answer
        }
    }

    private val confirming = PinGuardState(enabled = true, confirmInsteadOfBlock = true)
    private val guarding = PinGuardState(enabled = true, confirmInsteadOfBlock = false)

    private fun pinned(name: String, vararg pinnedIn: String) =
        PinnedFile(file = file(name), pinnedIn = pinnedIn.toList())

    private fun file(name: String): VirtualFile = LightVirtualFile(name, "")

    private fun gate(config: PinGuardState, prompt: ConfirmationPrompt) =
        PinnedCloseGate(configProvider = { config }, prompt = prompt)

    @Test
    fun `the prompt is told what the user calls each file, not its path`() {
        val prompt = RecordingPrompt(answer = true)

        gate(confirming, prompt).canClose(
            listOf(pinned("Main.kt", "acme-backend"), pinned("build.gradle.kts", "acme-backend")),
        )

        assertEquals(listOf("Main.kt", "build.gradle.kts"), prompt.lastNames)
    }

    @Test
    fun `a close spanning several pinned files still asks exactly one question`() {
        val prompt = RecordingPrompt(answer = true)

        gate(confirming, prompt).canClose(
            listOf(pinned("Main.kt", "acme-backend"), pinned("Other.kt", "acme-frontend")),
        )

        assertEquals(1, prompt.invocations)
    }

    @Test
    fun `nothing pinned is allowed without a prompt`() {
        val prompt = RecordingPrompt(answer = false)

        assertTrue(gate(confirming, prompt).canClose(emptyList()))
        assertEquals(0, prompt.invocations)
    }

    @Test
    fun `the decision is still the gate's, not this extension's`() {
        val prompt = RecordingPrompt(answer = true)

        assertFalse(gate(guarding, prompt).canClose(listOf(pinned("Main.kt", "acme-backend"))))
        assertEquals(0, prompt.invocations, "a blocking guard should never reach the prompt")
    }
}
