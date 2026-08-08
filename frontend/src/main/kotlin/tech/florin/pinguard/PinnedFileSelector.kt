package tech.florin.pinguard

import com.intellij.openapi.vfs.VirtualFile

/** A tab from a close request that is pinned, and where the pins were found. */
internal data class PinnedFile(
    val file: VirtualFile,
    /**
     * Labels of the editor windows that reported the pin; never empty.
     *
     * For the log only. A close refused because of a pin in a project the user is
     * not currently looking at is otherwise indistinguishable from a bug.
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
 * window rather than to the file: whether a pin stands in a close's way depends on
 * which window that close is aimed at.
 *
 * An interface so a test can hand back an answer — or a failure — without a
 * running IDE.
 */
internal fun interface PinnedFileSelector {
    fun pinnedAmong(targets: List<CloseTarget>): List<PinnedFile>
}

/**
 * Asks the gate about a set of pinned tabs.
 *
 * Here rather than on [PinnedCloseGate] itself so the gate stays free of platform
 * types, and in one place rather than at each call site so the two callers cannot
 * describe the same pins differently in the log.
 *
 * @return true if the close may proceed.
 */
internal fun PinnedCloseGate.canClose(pinned: List<PinnedFile>): Boolean =
    canClose(
        pinnedNames = pinned.map { it.presentableName },
        pinnedIn = pinned.flatMap { it.pinnedIn }.distinct(),
    )
