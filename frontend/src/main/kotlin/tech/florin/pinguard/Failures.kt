package tech.florin.pinguard

/**
 * Rethrows [failure] only if nothing sensible can be decided after it; otherwise
 * returns, leaving the caller to log and carry on.
 */
internal fun rethrowIfUnrecoverable(failure: Throwable) {
    // Cancellation is deliberately not rethrown, against the platform's usual
    // advice. Every catch site in this plugin is one where a throw costs more than
    // a wrong answer, and `AlreadyDisposedException` — the disposal race those
    // handlers exist for — has moved between `CancellationException` and
    // `ProcessCanceledException` across releases, so no version-stable rule tells
    // it from a real cancellation.
    if (failure is VirtualMachineError) throw failure
}
