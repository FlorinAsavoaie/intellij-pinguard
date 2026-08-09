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
     * @param pinnedNames presentable names of the pinned files in this close
     *   request; empty for a close that touches no pin.
     * @param pinnedIn where the pins were found, for the log only. Defaulted
     *   because it never affects the decision.
     * @return true if the close may proceed.
     */
    fun canClose(pinnedNames: List<String>, pinnedIn: List<String> = emptyList()): Boolean {
        val config = configProvider()
        val decision = decide(config, pinnedNames.size)

        val allowed = when (decision) {
            CloseDecision.ALLOW -> true
            CloseDecision.BLOCK -> false
            CloseDecision.ASK_USER -> prompt.confirmClosingPinned(pinnedNames)
        }

        if (pinnedNames.isNotEmpty()) {
            LOG.info(
                "$decision for $pinnedNames pinned in $pinnedIn " +
                    "(enabled=${config.enabled}, confirmInsteadOfBlock=${config.confirmInsteadOfBlock})" +
                    " -> allowed=$allowed",
            )
        }
        return allowed
    }

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
