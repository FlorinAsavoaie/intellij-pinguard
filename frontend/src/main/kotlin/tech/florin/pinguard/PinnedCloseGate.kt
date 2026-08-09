package tech.florin.pinguard

import com.intellij.openapi.diagnostic.Logger

private val LOG: Logger = Logger.getInstance(PinnedCloseGate::class.java)

/** Asks the user whether pinned tabs may be closed after all. */
internal fun interface ConfirmationPrompt {
    /**
     * @param pinnedNames presentable names of the pinned files in this close
     *   request; never empty.
     * @return true to let the close proceed.
     */
    fun confirmClosingPinned(pinnedNames: List<String>): Boolean
}

/** What PinGuard wants to happen to a close request. */
internal enum class CloseDecision {
    /** Nothing pinned, or the guard is off: let the platform close as usual. */
    ALLOW,

    /** Refuse the close outright. */
    BLOCK,

    /** Put the choice to the user. */
    ASK_USER,
}

/**
 * What the gate settled on.
 *
 * Three values rather than a yes and a no, because [CONFIRMED] means a dialog was up,
 * and a dialog leaves whatever the caller knew about the editor out of date.
 */
internal enum class CloseOutcome {
    /** Nothing stood in the close's way, and nobody was asked anything. */
    UNOPPOSED,

    /** A pin stood in the way, the user was asked, and said close it anyway. */
    CONFIRMED,

    /** A pin stood in the way and stays there. */
    REFUSED,
    ;

    /** Whether the close may happen at all. */
    val allowsClosing: Boolean get() = this != REFUSED
}

/**
 * Decides what should happen to a close request that involves pinned tabs, asking
 * the user when the settings call for it.
 *
 * [configProvider] is read on every call rather than captured once, so changing
 * the settings takes effect without recreating the guard.
 */
internal class PinnedCloseGate(
    private val configProvider: () -> PinGuardState,
    private val prompt: ConfirmationPrompt,
) {
    /**
     * What should happen to a close request that stands to take [pinnedNames] with it.
     *
     * @param pinnedNames presentable names of the pinned files in this close
     *   request; empty for a close that touches no pin.
     * @param pinnedIn where the pins were found, for the log only. Defaulted
     *   because it never affects the outcome.
     */
    fun outcomeFor(pinnedNames: List<String>, pinnedIn: List<String> = emptyList()): CloseOutcome {
        val config = configProvider()
        val decision = decide(config, pinnedNames.size)

        val outcome = when (decision) {
            CloseDecision.ALLOW -> CloseOutcome.UNOPPOSED
            CloseDecision.BLOCK -> CloseOutcome.REFUSED
            CloseDecision.ASK_USER ->
                if (prompt.confirmClosingPinned(pinnedNames)) CloseOutcome.CONFIRMED else CloseOutcome.REFUSED
        }

        if (pinnedNames.isNotEmpty()) {
            LOG.info(
                "$decision for $pinnedNames pinned in $pinnedIn " +
                    "(enabled=${config.enabled}, confirmInsteadOfBlock=${config.confirmInsteadOfBlock})" +
                    " -> $outcome",
            )
        }
        return outcome
    }

    /**
     * The same question, for callers with only a yes or a no to act on.
     *
     * @return true if the close may proceed.
     */
    fun canClose(pinnedNames: List<String>, pinnedIn: List<String> = emptyList()): Boolean =
        outcomeFor(pinnedNames, pinnedIn).allowsClosing

    private fun decide(config: PinGuardState, pinnedFileCount: Int): CloseDecision = when {
        !config.enabled -> CloseDecision.ALLOW
        pinnedFileCount <= 0 -> CloseDecision.ALLOW
        config.confirmInsteadOfBlock -> CloseDecision.ASK_USER
        else -> CloseDecision.BLOCK
    }
}

/**
 * The gate as the running IDE builds it: user settings, and a real dialog.
 *
 * Both of PinGuard's entry points — the action guards and the pre-close check —
 * come through here, so they cannot disagree about which settings are read or
 * which prompt the user sees.
 */
internal fun platformGate(): PinnedCloseGate = PinnedCloseGate(
    configProvider = { PinGuardSettings.getInstance().config },
    prompt = MessagesConfirmationPrompt,
)
