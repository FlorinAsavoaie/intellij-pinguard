package tech.florin.pinguard

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wording of the confirmation dialog, without putting one on screen.
 *
 * The dialog itself is [com.intellij.openapi.ui.Messages]' business; what
 * belongs to PinGuard is the arithmetic that keeps a "Close All" over fifty
 * pinned tabs from becoming an unreadable wall of names, and the singular the
 * common case deserves.
 */
class MessagesConfirmationPromptTest {

    private fun message(vararg names: String) = MessagesConfirmationPrompt.messageFor(names.toList())

    private fun title(vararg names: String) = MessagesConfirmationPrompt.titleFor(names.toList())

    @Test
    fun `one pinned tab is named in the singular, without a bullet list`() {
        val text = message("Main.kt")

        assertTrue(text.contains("Main.kt"))
        assertFalse(text.contains("•"), "a list of one is just a sentence")
        assertEquals("Close Pinned Tab", title("Main.kt"))
    }

    @Test
    fun `several pinned tabs are counted and listed`() {
        val text = message("Main.kt", "build.gradle.kts")

        assertTrue(text.startsWith("2 pinned tabs"))
        assertTrue(text.contains("  • Main.kt"))
        assertTrue(text.contains("  • build.gradle.kts"))
        assertEquals("Close Pinned Tabs", title("Main.kt", "build.gradle.kts"))
    }

    @Test
    fun `exactly ten tabs are all listed, with nothing trailing`() {
        val names = (1..10).map { "File$it.kt" }

        val text = MessagesConfirmationPrompt.messageFor(names)

        assertEquals(10, text.lines().count { it.startsWith("  • ") })
        assertFalse(text.contains("and 0 more"), "an off-by-one here reads as a bug in the count")
        assertFalse(text.contains("more"))
    }

    @Test
    fun `the eleventh tab becomes a remainder line rather than an eleventh bullet`() {
        val names = (1..11).map { "File$it.kt" }

        val text = MessagesConfirmationPrompt.messageFor(names)

        assertEquals(10, text.lines().count { it.startsWith("  • ") })
        assertTrue(text.contains("… and 1 more"))
    }

    @Test
    fun `a long close lists ten and counts the rest`() {
        val names = (1..57).map { "File$it.kt" }

        val text = MessagesConfirmationPrompt.messageFor(names)

        assertEquals(10, text.lines().count { it.startsWith("  • ") })
        assertTrue(text.contains("… and 47 more"))
        assertTrue(text.startsWith("57 pinned tabs"), "the count has to be of everything, not of what is listed")
    }

    @Test
    fun `the tabs that are listed are the first ones, so the list is stable`() {
        val names = (1..20).map { "File$it.kt" }

        val text = MessagesConfirmationPrompt.messageFor(names)

        assertTrue(text.contains("  • File1.kt"))
        assertTrue(text.contains("  • File10.kt"))
        assertFalse(text.lines().any { it == "  • File11.kt" })
    }
}
