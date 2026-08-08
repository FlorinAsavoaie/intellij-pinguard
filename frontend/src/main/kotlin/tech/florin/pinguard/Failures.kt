package tech.florin.pinguard

/**
 * Rethrows [failure] only if nothing sensible can be decided after it; otherwise
 * returns, leaving the caller to log and carry on.
 *
 * A [VirtualMachineError] is the only such failure: after one, the next decision
 * is not going to be any better than this one.
 *
 * Cancellation is deliberately *not* rethrown, against the platform's usual
 * advice, for two reasons that hold at every one of this plugin's catch sites:
 *
 * - **A throw is worse than a wrong answer on all of them.** They sit on
 *   `canCloseFile` (where `FileEditorManagerImpl` has no try/catch of its own, so
 *   a throw is a tab that will not close), inside `actionPerformed` (where it is a
 *   close the user asked for and did not get), in `dispose` during shutdown (where
 *   a `ProcessCanceledException` escaping is itself an error the platform
 *   reports), and in the error-report submitter (where it wedges the Report button
 *   for the rest of the session). Answering "not pinned" and logging loses at most
 *   one pin.
 * - **The exception actually worth singling out cannot be named portably.** The
 *   disposal race throws `AlreadyDisposedException`, whose supertype has moved
 *   between platform releases — `CancellationException` on one,
 *   `ProcessCanceledException` on the next — so no version-stable rule
 *   distinguishes "the IDE is cancelling this" from "a service went away
 *   mid-close", which is the case these handlers exist for.
 */
internal fun rethrowIfUnrecoverable(failure: Throwable) {
    if (failure is VirtualMachineError) throw failure
}
