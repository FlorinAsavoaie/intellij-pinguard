package tech.florin.pinguard

/**
 * One section of a stack trace — the exception it started with, or a `Caused by:`
 * or `Suppressed:` block — as its header line and the frames underneath it.
 */
internal data class CauseBlock(
    val header: String,
    val frames: List<String>,
)

/**
 * Reads a stack trace as the structure it actually is, so that shortening one
 * keeps the part a maintainer needs.
 *
 * Cutting a trace off at the head loses the root cause, which in a wrapped
 * exception lives in the *last* block — so every block header survives every
 * quota, and frames are what gets spent.
 */
internal object StackTraceDigest {

    /**
     * No quota above this is honoured, which keeps [render]'s arithmetic in range:
     * the wrapper share multiplies the quota by [WRAPPER_PERCENT] before dividing,
     * and that product overflows for a large enough one.
     *
     * Not a defence against anything [GitHubIssueUrl] passes — its quotas are
     * bounded by the URL budget long before this. It guards [render] as a callable
     * function, which is how it is reached from tests and would be reached by any
     * future caller that has not thought about the arithmetic.
     */
    private const val MAX_FRAMES = 10_000

    /** Share of the quota the blocks between the two ends keep, as a percentage. */
    private const val WRAPPER_PERCENT = 35

    private val BACKTICK_RUN = Regex("`+")

    /**
     * [trace] with its line separators reduced to `\n` and trailing blank lines
     * dropped.
     *
     * `IdeaLoggingEvent.getThrowableText()` writes through a `PrintWriter`, so its
     * separators are the platform's — `\r\n` on Windows, which would otherwise
     * reach the issue as stray carriage returns.
     */
    fun normalize(trace: String): String =
        trace.replace("\r\n", "\n").replace("\r", "\n").trimEnd()

    /** [trace] split into its cause blocks, or an empty list if it is blank. */
    fun blocks(trace: String): List<CauseBlock> {
        val text = normalize(trace)
        if (text.isBlank()) return emptyList()

        val lines = text.split('\n')
        val blocks = mutableListOf<CauseBlock>()
        var header = lines.first()
        var frames = mutableListOf<String>()

        for (line in lines.drop(1)) {
            if (startsBlock(line)) {
                blocks += CauseBlock(header, frames.toList())
                header = line
                frames = mutableListOf()
            } else {
                frames += line
            }
        }
        blocks += CauseBlock(header, frames.toList())
        return blocks
    }

    /**
     * Re-renders [blocks], keeping at most [framesPerBlock] frames of each and
     * counting what it dropped.
     *
     * The first block is where it blew up and the last is the root cause, so both
     * keep the full quota; the wrappers strung between them are the least
     * informative part of a deep trace and keep a fraction of it.
     */
    fun render(blocks: List<CauseBlock>, framesPerBlock: Int): String {
        if (blocks.isEmpty()) return ""

        val quota = framesPerBlock.coerceIn(0, MAX_FRAMES)
        val last = blocks.size - 1

        return blocks.flatMapIndexed { index, block ->
            val keep = when {
                index == 0 || index == last -> quota
                quota == 0 -> 0
                else -> maxOf(1, quota * WRAPPER_PERCENT / 100)
            }
            val kept = block.frames.take(keep)
            val omitted = block.frames.size - kept.size

            buildList {
                add(block.header)
                addAll(kept)
                if (omitted > 0) add("\t... $omitted ${if (omitted == 1) "frame" else "frames"} omitted ...")
            }
        }.joinToString("\n")
    }

    /**
     * A markdown fence long enough that nothing in [trace] can break out of the
     * code block.
     *
     * A trace whose message embeds a markdown snippet otherwise ends the fence
     * early and GitHub renders the rest of the report as prose.
     */
    fun fenceFor(trace: String): String {
        val longestRun = BACKTICK_RUN.findAll(trace).maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(maxOf(3, longestRun + 1))
    }

    /** The first line of [trace] with anything on it, trimmed, or `""` if it is blank. */
    fun firstLine(trace: String): String =
        normalize(trace).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun startsBlock(line: String): Boolean {
        // Trimmed rather than anchored: both markers are indented when the exception
        // they belong to is itself inside a `Suppressed:` block.
        val trimmed = line.trimStart()
        return trimmed.startsWith("Caused by:") || trimmed.startsWith("Suppressed:")
    }
}
