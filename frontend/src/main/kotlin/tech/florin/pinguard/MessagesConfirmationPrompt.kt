package tech.florin.pinguard

import com.intellij.openapi.ui.Messages

/**
 * The real confirmation dialog. Runs on the EDT, which is where the platform's
 * pre-close checks already are.
 */
internal object MessagesConfirmationPrompt : ConfirmationPrompt {

    /** Keeps a long "Close All" from producing an unreadable wall of names. */
    private const val MAX_LISTED = 10

    override fun confirmClosingPinned(pinnedNames: List<String>): Boolean {
        val answer = Messages.showYesNoDialog(
            messageFor(pinnedNames),
            titleFor(pinnedNames),
            PinGuardBundle.message("confirm.yes"),
            PinGuardBundle.message("confirm.no"),
            Messages.getQuestionIcon(),
        )
        return answer == Messages.YES
    }

    /**
     * The dialog title for [names].
     *
     * Internal so the wording can be asserted without putting a dialog on screen.
     */
    internal fun titleFor(names: List<String>): String = if (names.size == 1) {
        PinGuardBundle.message("confirm.title")
    } else {
        PinGuardBundle.message("confirm.title.plural")
    }

    /**
     * The dialog body for [names]: a sentence for one file, otherwise a count and
     * a list of at most [MAX_LISTED] of them.
     *
     * Internal for the same reason as [titleFor].
     */
    internal fun messageFor(names: List<String>): String {
        if (names.size == 1) {
            return PinGuardBundle.message("confirm.message.single", names.single())
        }
        val listed = names.take(MAX_LISTED)
            .joinToString("\n") { PinGuardBundle.message("confirm.message.item", it) }
        val remainder = names.size - MAX_LISTED
        val body = if (remainder > 0) {
            listed + "\n" + PinGuardBundle.message("confirm.message.more", remainder)
        } else {
            listed
        }
        return PinGuardBundle.message("confirm.message.multiple", names.size, body)
    }
}
