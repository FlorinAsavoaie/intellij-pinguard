package tech.florin.pinguard

import com.intellij.openapi.vfs.VirtualFile

/** A tab from a close request that is pinned, and where the pins were found. */
internal data class PinnedFile(
    val file: VirtualFile,
    /**
     * Labels of the editor windows that reported the pin; never empty. For the log
     * only.
     */
    val pinnedIn: List<String>,
) {
    /** What the user calls this file, which is what a dialog has to say. */
    val presentableName: String get() = file.presentableName
}

/**
 * Picks out the pinned tabs from a close request.
 *
 * Takes [CloseTarget]s rather than bare files because a pin belongs to one editor
 * window rather than to the file: whether it stands in a close's way depends on
 * which window that close is aimed at.
 */
internal fun interface PinnedFileSelector {
    fun pinnedAmong(targets: List<CloseTarget>): List<PinnedFile>
}

/**
 * Asks the gate about a set of pinned tabs.
 *
 * An extension so [PinnedCloseGate] itself stays free of platform types.
 */
internal fun PinnedCloseGate.outcomeFor(pinned: List<PinnedFile>): CloseOutcome =
    outcomeFor(
        pinnedNames = pinned.map { it.presentableName },
        pinnedIn = pinned.flatMap { it.pinnedIn }.distinct(),
    )

/**
 * The same question, for callers with only a yes or a no to act on.
 *
 * @return true if the close may proceed.
 */
internal fun PinnedCloseGate.canClose(pinned: List<PinnedFile>): Boolean = outcomeFor(pinned).allowsClosing
