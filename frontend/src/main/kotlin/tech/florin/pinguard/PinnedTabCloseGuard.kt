package tech.florin.pinguard

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck

private val LOG: Logger = Logger.getInstance(PinnedTabCloseGuard::class.java)

/**
 * Vetoes closing a pinned editor tab, on the one close path the platform offers a
 * supported veto for.
 *
 * `com.intellij.virtualFilePreCloseCheck` is consulted only by
 * `FileEditorManagerImpl.closeFileWithChecks`, which today is reached only from
 * the `CloseEditor` action — no keyboard shortcut, in no menu. The closes users
 * actually perform are guarded by [PinGuardActionGuards] instead.
 *
 * The extension point is `@ApiStatus.Experimental` and may change or disappear
 * without a deprecation cycle; losing it costs the `CloseEditor` path and nothing
 * else.
 *
 * Programmatic closes, including closing the project itself, bypass these checks,
 * so a veto here cannot strand the IDE.
 *
 * Public because the module descriptor names it and the platform instantiates it
 * reflectively.
 */
public class PinnedTabCloseGuard internal constructor(
    private val selector: PinnedFileSelector,
    private val gate: PinnedCloseGate,
) : VirtualFilePreCloseCheck {

    /** Used by the platform when instantiating the extension. */
    @Suppress("unused")
    public constructor() : this(
        selector = PlatformPinnedFiles.selector(),
        gate = platformGate(),
    )

    /**
     * Decides whether [file] may be closed, asking the user first when the
     * settings call for it.
     *
     * A guard that cannot reach a decision allows the close:
     * `FileEditorManagerImpl.canCloseFile` iterates the extension point with no
     * try/catch of its own, so anything thrown here would abort the close and
     * leave the user with a tab that will not shut.
     *
     * @return true if the platform may go ahead and close the tab.
     */
    override fun canCloseFile(file: VirtualFile): Boolean =
        try {
            // No window: this path closes the file across every split, so every
            // window's pin is in its way.
            gate.canClose(selector.pinnedAmong(listOf(CloseTarget(file, window = null))))
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not decide whether '${file.name}' is pinned; allowing the close", failure)
            true
        }
}
