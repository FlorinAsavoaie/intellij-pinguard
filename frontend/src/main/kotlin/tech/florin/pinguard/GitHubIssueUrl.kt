package tech.florin.pinguard

import org.jetbrains.annotations.NonNls
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@NonNls
private const val NEW_ISSUE_URL = "https://github.com/FlorinAsavoaie/intellij-pinguard/issues/new"

/** Everything an issue is made of, gathered before the last platform type is left behind. */
internal data class IssueReport(
    /** `IdeaLoggingEvent.getMessage()`, which is genuinely null for many logged errors. */
    val message: String?,
    /** `IdeaLoggingEvent.getThrowableText()`: the whole formatted trace, or `""`. */
    val stackTrace: String,
    /** Whatever the user typed into the error dialog; null when they typed nothing. */
    val userComment: String?,
    val environment: ReportEnvironment,
)

/**
 * A report ready to hand to the outside world.
 *
 * [fullReport] is the body as it would read with nothing cut. It is what the user
 * pastes when [truncated] is true, and goes unused otherwise.
 */
internal data class PreparedIssue(
    val url: String,
    val fullReport: String,
    val truncated: Boolean,
)

/**
 * Composes a prefilled GitHub issue that fits in a URL.
 *
 * Only `title` and `body` are ever sent. GitHub's documentation is explicit that
 * `labels`, `assignees`, `milestone` and `projects` return 404 to anyone without
 * the matching permission on the repository — and almost everyone who reports a
 * crash is a stranger to it, so the one thing those parameters would reliably do
 * is replace the bug report with an error page.
 */
internal object GitHubIssueUrl {

    /**
     * Byte cap on the whole absolute URL.
     *
     * GitHub answers 414 at exactly 8192 bytes, but that wall is never reached: it
     * enforces a combined budget of roughly 7065 bytes across the URL *and* the
     * request headers, and a logged-in browser spends 1.5–2.5 KB of that on session
     * cookies alone. Overshooting produces a bare "Internal server error" page,
     * with nothing for the plugin to detect — so the budget has to be conservative
     * by construction rather than by retry.
     */
    const val MAX_URL_BYTES = 4000

    private const val MAX_TITLE_CHARS = 120
    private const val MAX_TITLE_BYTES = 300
    private const val MAX_COMMENT_CHARS = 600

    /** Frames per block; the ceiling of the search, not a value anything renders at. */
    private const val MAX_QUOTA = 400

    @NonNls
    private const val FALLBACK_TITLE = "Unhandled exception"

    // Neither note names the stack trace as the culprit: a comment written in a
    // script that costs three bytes a character can exhaust the budget on its own,
    // and a note blaming a six-line trace misleads everyone who reads it.
    @NonNls
    private const val TRUNCATED_NOTE =
        "_Only part of this report fits in a prefilled URL. " +
            "The whole of it is on your clipboard; please paste it below._"

    @NonNls
    private const val OMITTED_NOTE =
        "_This report was too large for a prefilled URL. " +
            "The whole of it is on your clipboard; please paste it below._"

    private val WHITESPACE = Regex("\\s+")

    /**
     * Prepares [report] as a prefilled issue URL, cutting it down until it fits
     * [MAX_URL_BYTES] and saying so in the body when it had to.
     */
    fun forNewIssue(report: IssueReport): PreparedIssue {
        val prefix = "$NEW_ISSUE_URL?title=${encode(title(report))}&body="
        val budget = MAX_URL_BYTES - prefix.length

        val environment = report.environment
        val fullComment = report.userComment?.trim()?.takeIf { it.isNotEmpty() }
        val trace = StackTraceDigest.normalize(report.stackTrace).takeIf { it.isNotEmpty() }

        // The clipboard payload, and the only copy with nothing taken out of it —
        // including the user's own words, which are the one part of a report that
        // cannot be reconstructed afterward.
        val fullReport = compose(fullComment, environment, trace, note = null)
        val encodedFull = encode(fullReport)
        if (encodedFull.length <= budget) {
            return PreparedIssue(prefix + encodedFull, fullReport, truncated = false)
        }

        val comment = fullComment?.let { abbreviate(it, MAX_COMMENT_CHARS) }
        val blocks = trace?.let { StackTraceDigest.blocks(it) }

        // Spend what is left on the trace first and on the comment second. Each rung
        // gives up the cheapest thing that might make the next one fit, and every
        // one of them is measured rather than estimated.
        val body = blocks
            ?.let { parsed ->
                largestFitting(budget, MAX_QUOTA) { quota ->
                    compose(comment, environment, StackTraceDigest.render(parsed, quota), TRUNCATED_NOTE)
                }
                    // Even one frame per block can overrun the budget: a deep enough
                    // cause chain, or a single exception carrying a message thousands
                    // of characters long, spends more on headers than there is room
                    // for. Clipping keeps the head of the trace instead of discarding
                    // all of it and leaving most of the URL unused.
                    ?: run {
                        val headers = StackTraceDigest.render(parsed, framesPerBlock = 0)
                        largestFitting(budget, headers.codePointCount(0, headers.length)) { chars ->
                            compose(
                                comment,
                                environment,
                                abbreviate(headers, chars).takeIf { it.isNotEmpty() },
                                TRUNCATED_NOTE,
                            )
                        }
                    }
            }
            // The comment has to be shortened rather than dropped whole: capping it
            // by code points bounds nothing in bytes, so a comment in a script that
            // costs three bytes a character can fail every rung above by itself.
            ?: comment?.let { text ->
                largestFitting(budget, text.codePointCount(0, text.length)) { chars ->
                    compose(abbreviate(text, chars), environment, trace = null, note = OMITTED_NOTE)
                }
            }
            ?: compose(comment = null, environment, trace = null, note = OMITTED_NOTE)
                .takeIf { encode(it).length <= budget }
            ?: ""

        return PreparedIssue(prefix + encode(body), fullReport, truncated = true)
    }

    /**
     * The issue title: the event's message if it has one, else the first line of
     * the trace, collapsed to one line and cut to fit.
     *
     * Exposed so the title rules can be asserted without decoding a URL.
     */
    fun title(report: IssueReport): String {
        val raw = report.message?.takeIf { it.isNotBlank() }
            ?: StackTraceDigest.firstLine(report.stackTrace)
        val collapsed = raw.replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return FALLBACK_TITLE

        // The character cap is what usually bites; the byte search below is for
        // titles made of characters that cost up to twelve bytes each once encoded.
        val capped = abbreviate(collapsed, MAX_TITLE_CHARS)
        if (encode(capped).length <= MAX_TITLE_BYTES) return capped

        // Unlike the body searches, this one is exact: every candidate below
        // `capped` is shorter than it and so carries the ellipsis, which makes
        // encoded length rise strictly with the code-point count. The smallest
        // candidate is the empty string, so there is always one that fits.
        return largestFitting(MAX_TITLE_BYTES, capped.codePointCount(0, capped.length) - 1) { chars ->
            abbreviate(capped, chars)
        }.orEmpty()
    }

    /**
     * The largest rendering [candidate] produces that still fits [budget], or null
     * if even its smallest does not.
     *
     * Length is only weakly monotone in the input — at the exact point a block's
     * "frames omitted" line disappears, or a clip cuts away the run of backticks
     * that was setting the fence width, the rendering can shrink slightly — so this
     * finds *a* large fitting size rather than provably the largest. A conservative
     * answer is still a correct one, because every candidate it accepts was
     * measured; the alternative is hundreds of sequential encodings on the EDT.
     */
    private fun largestFitting(budget: Int, ceiling: Int, candidate: (Int) -> String): String? {
        var low = 0
        var high = ceiling
        var best: String? = null

        while (low <= high) {
            val mid = (low + high) / 2
            val text = candidate(mid)
            if (encode(text).length <= budget) {
                best = text
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    private fun compose(
        comment: String?,
        environment: ReportEnvironment,
        trace: String?,
        note: String?,
    ): String {
        val sections = mutableListOf<String>()

        if (comment != null) sections += "### What happened\n\n$comment"
        sections += "### Environment\n\n${environment.asMarkdown()}"

        // The note stands on its own rather than under the heading: a report with no
        // trace left in it should not carry a "Stack trace" section promising one.
        if (note != null) sections += note

        if (trace != null) {
            // Sized from the trace being rendered rather than the one it was cut
            // from. A fence long enough for a run of backticks that has since been
            // cut away can cost more of the budget than the trace it wraps.
            val fence = StackTraceDigest.fenceFor(trace)
            sections += "### Stack trace\n\n$fence" + "text\n" + trace + "\n" + fence
        }

        return sections.joinToString("\n\n") + "\n"
    }

    /**
     * [text] cut to [maxCodePoints], with an ellipsis where anything was dropped.
     *
     * Counted in code points rather than chars, so a cut never lands inside a
     * surrogate pair — half a pair encodes to `%3F` and the character is lost.
     */
    private fun abbreviate(text: String, maxCodePoints: Int): String = when {
        maxCodePoints <= 0 -> ""
        maxCodePoints == 1 -> "…"
        text.codePointCount(0, text.length) <= maxCodePoints -> text
        else -> text.substring(0, text.offsetByCodePoints(0, maxCodePoints - 1)) + "…"
    }

    /**
     * [value] as a query-string parameter.
     *
     * GitHub parses the query as form-urlencoded, so `+` for a space is correct and
     * is what its own documentation shows. The output is ASCII, which is why every
     * budget check in this file can measure it with [String.length].
     */
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
