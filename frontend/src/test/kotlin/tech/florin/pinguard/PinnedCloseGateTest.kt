package tech.florin.pinguard

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PinnedCloseGateTest {

    /** Records what it was asked, and answers however the test tells it to. */
    private class FakePrompt(private val answer: Boolean) : ConfirmationPrompt {
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

    private fun gate(config: PinGuardState, prompt: ConfirmationPrompt) =
        PinnedCloseGate(configProvider = { config }, prompt = prompt)

    private val guarding = PinGuardState(enabled = true, confirmInsteadOfBlock = false)
    private val confirming = PinGuardState(enabled = true, confirmInsteadOfBlock = true)
    private val off = PinGuardState(enabled = false, confirmInsteadOfBlock = false)

    @Test
    fun `closing nothing pinned is allowed without bothering the user`() {
        val prompt = FakePrompt(answer = false)
        assertTrue(gate(guarding, prompt).canClose(pinnedNames = emptyList()))
        assertEquals(0, prompt.invocations)
    }

    @Test
    fun `guarding blocks silently and never prompts`() {
        val prompt = FakePrompt(answer = true)
        assertFalse(gate(guarding, prompt).canClose(pinnedNames = listOf("Main.kt")))
        assertEquals(0, prompt.invocations)
    }

    @Test
    fun `confirming defers to the user saying yes`() {
        val prompt = FakePrompt(answer = true)
        assertTrue(gate(confirming, prompt).canClose(pinnedNames = listOf("Main.kt")))
        assertEquals(1, prompt.invocations)
    }

    @Test
    fun `confirming defers to the user saying no`() {
        val prompt = FakePrompt(answer = false)
        assertFalse(gate(confirming, prompt).canClose(pinnedNames = listOf("Main.kt")))
        assertEquals(1, prompt.invocations)
    }

    @Test
    fun `the prompt is told every pinned file, so it can ask about them all at once`() {
        val prompt = FakePrompt(answer = true)
        gate(confirming, prompt).canClose(pinnedNames = listOf("Main.kt", "build.gradle.kts"))

        assertEquals(1, prompt.invocations, "a multi-file close should ask exactly one question")
        assertEquals(listOf("Main.kt", "build.gradle.kts"), prompt.lastNames)
    }

    @Test
    fun `a disabled guard stays out of the way entirely`() {
        val prompt = FakePrompt(answer = false)
        assertTrue(gate(off, prompt).canClose(pinnedNames = listOf("Main.kt")))
        assertEquals(0, prompt.invocations)
    }

    @Test
    fun `settings are re-read per close, so toggling them takes effect immediately`() {
        var config = guarding
        val gate = PinnedCloseGate(configProvider = { config }, prompt = FakePrompt(answer = true))

        assertFalse(gate.canClose(pinnedNames = listOf("Main.kt")))
        config = off
        assertTrue(gate.canClose(pinnedNames = listOf("Main.kt")))
    }
}
