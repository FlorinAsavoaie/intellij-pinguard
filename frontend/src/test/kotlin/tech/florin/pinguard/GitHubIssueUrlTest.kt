package tech.florin.pinguard

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The URL composer. Its one unconditional promise is that whatever it is handed,
 * the URL it produces is short enough for GitHub to accept — overshooting shows
 * the user a bare "Internal server error" that the plugin cannot detect.
 */
class GitHubIssueUrlTest {

    private val environment = ReportEnvironment(
        plugin = "tech.florin.pinguard 0.1.0",
        ide = "IntelliJ IDEA 2025.2.5",
        build = "IC-252.28238.7",
        os = "macOS 15.5",
        jvm = "21.0.7+6 (JetBrains s.r.o.)",
        locale = "en-US",
    )

    private fun report(
        message: String? = "java.lang.IllegalStateException: boom",
        stackTrace: String = shortTrace,
        userComment: String? = null,
    ) = IssueReport(
        message = message,
        stackTrace = stackTrace,
        userComment = userComment,
        environment = environment,
    )

    private val shortTrace = listOf(
        "java.lang.IllegalStateException: boom",
        "\tat tech.florin.One.a(One.kt:11)",
        "\tat tech.florin.One.b(One.kt:12)",
    ).joinToString("\n")

    private fun deepTrace(framesPerBlock: Int): String = listOf(
        "java.lang.IllegalStateException: outer",
        *Array(framesPerBlock) { "\tat tech.florin.One.frame$it(VeryLongFileName.kt:$it)" },
        "Caused by: java.io.IOException: the actual problem",
        *Array(framesPerBlock) { "\tat tech.florin.Three.frame$it(VeryLongFileName.kt:$it)" },
    ).joinToString("\n")

    @Test
    fun `a short report is prefilled whole`() {
        val prepared = GitHubIssueUrl.forNewIssue(report())

        assertFalse(prepared.truncated)
        assertTrue(prepared.url.startsWith("https://github.com/FlorinAsavoaie/intellij-pinguard/issues/new?"))
        assertTrue(prepared.fullReport.contains("tech.florin.One.a"))
    }

    @Test
    fun `the url never exceeds the byte budget, whatever it is handed`() {
        val monstrous = report(
            message = "x".repeat(5_000),
            stackTrace = deepTrace(framesPerBlock = 3_000),
            userComment = "y".repeat(50_000),
        )

        val prepared = GitHubIssueUrl.forNewIssue(monstrous)

        assertTrue(
            prepared.url.length <= GitHubIssueUrl.MAX_URL_BYTES,
            "a URL over the budget shows GitHub's error page instead of the issue form",
        )
    }

    @Test
    fun `a huge trace is cut down and the issue says so`() {
        val prepared = GitHubIssueUrl.forNewIssue(report(stackTrace = deepTrace(framesPerBlock = 400)))

        assertTrue(prepared.truncated)
        assertTrue(prepared.url.contains("clipboard"))
    }

    @Test
    fun `a cut-down report still carries the root cause of a wrapped exception`() {
        val prepared = GitHubIssueUrl.forNewIssue(report(stackTrace = deepTrace(framesPerBlock = 400)))

        assertTrue(
            prepared.url.contains("java.io.IOException"),
            "head-only truncation would drop the last block, which is where the root cause is",
        )
    }

    @Test
    fun `the untouched report is always kept, so there is something to paste`() {
        val trace = deepTrace(framesPerBlock = 400)
        val prepared = GitHubIssueUrl.forNewIssue(report(stackTrace = trace))

        assertTrue(prepared.fullReport.contains("tech.florin.One.frame399"))
        assertTrue(prepared.fullReport.contains("tech.florin.Three.frame399"))
    }

    @Test
    fun `a report that fits is not marked as cut`() {
        assertFalse(GitHubIssueUrl.forNewIssue(report()).truncated)
    }

    @Test
    fun `the title falls back to the first line of the trace when the event carried no message`() {
        assertEquals("java.lang.IllegalStateException: boom", GitHubIssueUrl.title(report(message = null)))
    }

    @Test
    fun `a report with neither a message nor a trace still gets a usable title`() {
        assertEquals("Unhandled exception", GitHubIssueUrl.title(report(message = null, stackTrace = "")))
        assertEquals("Unhandled exception", GitHubIssueUrl.title(report(message = "   ", stackTrace = "  ")))
    }

    @Test
    fun `a long title is cut to a fixed length`() {
        val title = GitHubIssueUrl.title(report(message = "boom ".repeat(400)))

        assertTrue(title.length <= 121, "120 code points plus the ellipsis")
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `an emoji title is never cut through the middle of a character`() {
        val title = GitHubIssueUrl.title(report(message = "🐛".repeat(300)))

        assertEquals(0, title.count { it.isSurrogate() } % 2, "a lone surrogate encodes to %3F and is lost")
    }

    @Test
    fun `a title made only of emoji still fits the title budget`() {
        val title = GitHubIssueUrl.title(report(message = "🐛".repeat(300)))

        // Each emoji costs twelve bytes encoded, so the character cap alone leaves a
        // title four times over the byte budget: it is the shrink loop and not the
        // cap that has to bound this.
        assertTrue(
            URLEncoder.encode(title, StandardCharsets.UTF_8).length <= 300,
            "asserting code points here would pass on any implementation, cap or no cap",
        )
    }

    @Test
    fun `a message spread over several lines becomes a single-line title`() {
        val title = GitHubIssueUrl.title(report(message = "boom\n\tat somewhere\n\tat elsewhere"))

        assertEquals("boom at somewhere at elsewhere", title)
    }

    @Test
    fun `an absent comment leaves out the what-happened section`() {
        assertFalse(GitHubIssueUrl.forNewIssue(report(userComment = null)).fullReport.contains("What happened"))
    }

    @Test
    fun `a blank comment leaves out the what-happened section`() {
        assertFalse(GitHubIssueUrl.forNewIssue(report(userComment = "   ")).fullReport.contains("What happened"))
    }

    @Test
    fun `a very long comment is cut before it can crowd out the stack trace`() {
        val prepared = GitHubIssueUrl.forNewIssue(report(userComment = "z".repeat(5_000)))

        assertTrue(prepared.url.length <= GitHubIssueUrl.MAX_URL_BYTES)
        assertTrue(prepared.truncated)
        assertFalse(prepared.url.contains("z".repeat(700)), "the comment must be the thing that gave way")
        assertTrue(
            prepared.url.contains("tech.florin.One.a"),
            "the heading alone proves nothing; a frame has to survive",
        )
    }

    @Test
    fun `a comment written in a wide script does not evict the whole report`() {
        // 400 CJK characters is inside the code-point cap but three bytes each once
        // encoded, which is enough to fail every rung that drops the comment whole
        // rather than shortening it.
        val prepared = GitHubIssueUrl.forNewIssue(report(userComment = "中".repeat(400)))

        assertTrue(prepared.url.length <= GitHubIssueUrl.MAX_URL_BYTES)
        assertTrue(
            prepared.url.length > GitHubIssueUrl.MAX_URL_BYTES / 2,
            "dropping the comment wholesale leaves most of the budget unspent",
        )
        assertTrue(prepared.url.contains("%E4%B8%AD"), "the user's own words are what cannot be reconstructed")
    }

    @Test
    fun `a comment too wide for any trace still keeps as much of itself as fits`() {
        val prepared = GitHubIssueUrl.forNewIssue(
            report(stackTrace = deepTrace(framesPerBlock = 400), userComment = "中".repeat(4_000)),
        )

        assertTrue(prepared.url.length <= GitHubIssueUrl.MAX_URL_BYTES)
        assertTrue(prepared.url.length > GitHubIssueUrl.MAX_URL_BYTES / 2)
        assertTrue(prepared.url.contains("%E4%B8%AD"))
    }

    @Test
    fun `the clipboard copy keeps the user's words entire, however long they were`() {
        val comment = "z".repeat(5_000)
        val prepared = GitHubIssueUrl.forNewIssue(report(userComment = comment))

        assertTrue(
            prepared.fullReport.contains(comment),
            "the report on the clipboard is the only copy of what the user typed",
        )
    }

    @Test
    fun `a comment long enough to be cut is always marked, so it reaches the clipboard`() {
        // Nothing else would copy it: the submitter only reaches for the clipboard
        // when the issue says something was left out.
        assertTrue(GitHubIssueUrl.forNewIssue(report(userComment = "z".repeat(5_000))).truncated)
    }

    @Test
    fun `a truncated report still carries real stack frames, not just the headers`() {
        val prepared = GitHubIssueUrl.forNewIssue(report(stackTrace = deepTrace(framesPerBlock = 400)))

        val frames = Regex("tech\\.florin\\.(One|Three)\\.frame").findAll(prepared.url).count()

        assertTrue(prepared.truncated)
        assertTrue(frames > 5, "a search that always returned its smallest candidate would still pass without this")
    }

    @Test
    fun `a cause chain too deep to keep even one frame each still spends the budget`() {
        // Every block header survives every quota, so past about thirty nested
        // causes the headers alone overrun the budget and the search for a frame
        // quota fails outright.
        val chain = buildList {
            add("java.lang.IllegalStateException: outer")
            repeat(50) { block ->
                add("Caused by: java.io.IOException: connection reset by peer (attempt $block)")
                repeat(30) { add("\tat com.intellij.openapi.vfs.SomeServiceImpl.doWork(SomeServiceImpl.java:$it)") }
            }
        }.joinToString("\n")

        val prepared = GitHubIssueUrl.forNewIssue(report(stackTrace = chain))

        assertTrue(prepared.url.length <= GitHubIssueUrl.MAX_URL_BYTES)
        assertTrue(prepared.url.length > GitHubIssueUrl.MAX_URL_BYTES / 2, "the trace was dropped rather than clipped")
        assertTrue(prepared.url.contains("IOException"))
    }

    @Test
    fun `an exception whose message is enormous keeps as much of it as fits`() {
        val trace = listOf(
            "java.lang.IllegalArgumentException: bad query ${"S".repeat(3_500)}",
            *Array(200) { "\tat tech.florin.One.frame$it(One.kt:$it)" },
        ).joinToString("\n")

        val prepared = GitHubIssueUrl.forNewIssue(report(message = "boom", stackTrace = trace))

        assertTrue(prepared.url.length <= GitHubIssueUrl.MAX_URL_BYTES)
        assertTrue(prepared.url.length > GitHubIssueUrl.MAX_URL_BYTES / 2)
        assertTrue(prepared.url.contains("IllegalArgumentException"))
    }

    @Test
    fun `a fence forced wide by backticks is narrowed with the trace it wraps`() {
        // The fence has to outrun the longest backtick run in the text it wraps, so
        // sizing it from the uncut trace can cost more than the trace itself.
        val trace = listOf(
            "java.lang.RuntimeException: see ${"`".repeat(400)} here",
            *Array(200) { "\tat tech.florin.One.frame$it(One.kt:$it)" },
        ).joinToString("\n")

        val prepared = GitHubIssueUrl.forNewIssue(report(message = "boom", stackTrace = trace))

        assertTrue(prepared.url.length <= GitHubIssueUrl.MAX_URL_BYTES)
        assertTrue(prepared.url.length > GitHubIssueUrl.MAX_URL_BYTES / 2)
    }

    @Test
    fun `a report with nothing left to give up says so without promising a stack trace`() {
        val prepared = GitHubIssueUrl.forNewIssue(
            report(stackTrace = "", userComment = "中".repeat(4_000)),
        )

        assertTrue(prepared.url.length <= GitHubIssueUrl.MAX_URL_BYTES)
        assertTrue(prepared.truncated)
        assertFalse(
            prepared.url.contains("Stack+trace"),
            "a section heading with no frames under it misdirects whoever reads the issue",
        )
    }

    @Test
    fun `an empty stack trace leaves out the stack-trace section entirely`() {
        val prepared = GitHubIssueUrl.forNewIssue(report(stackTrace = ""))

        assertFalse(prepared.fullReport.contains("Stack trace"))
        assertFalse(prepared.truncated)
    }

    @Test
    fun `the environment block names the plugin, the ide, the os, the jvm and the locale`() {
        val body = GitHubIssueUrl.forNewIssue(report()).fullReport

        assertTrue(body.contains("tech.florin.pinguard 0.1.0"))
        assertTrue(body.contains("IntelliJ IDEA 2025.2.5 (IC-252.28238.7)"))
        assertTrue(body.contains("macOS 15.5"))
        assertTrue(body.contains("21.0.7+6 (JetBrains s.r.o.)"))
        assertTrue(body.contains("en-US"))
    }

    @Test
    fun `a trace containing a markdown fence cannot break out of the code block`() {
        val trace = "java.lang.RuntimeException: see ``` this\n\tat tech.florin.One.a(One.kt:1)"
        val body = GitHubIssueUrl.forNewIssue(report(stackTrace = trace)).fullReport

        assertTrue(body.contains("````text"), "the fence must outrun the longest backtick run inside it")
    }

    @Test
    fun `unicode in the message survives as utf-8 percent escapes`() {
        val prepared = GitHubIssueUrl.forNewIssue(report(message = "café ✓"))

        assertTrue(prepared.url.contains("caf%C3%A9"))
        assertTrue(prepared.url.contains("%E2%9C%93"))
    }

    @Test
    fun `newlines in the body are encoded rather than sent raw`() {
        val prepared = GitHubIssueUrl.forNewIssue(report())

        assertFalse(prepared.url.contains("\n"))
        assertTrue(prepared.url.contains("%0A"))
    }

    @Test
    fun `the url is one query string, with nothing unencoded to split it`() {
        val prepared = GitHubIssueUrl.forNewIssue(report(message = "a?b&c=d", userComment = "e&f?g"))

        assertEquals(1, prepared.url.count { it == '?' })
        assertEquals(1, prepared.url.count { it == '&' })
    }

    @Test
    fun `labels, assignees and milestones are never asked for`() {
        // GitHub answers 404 to anyone without the matching permission on the
        // repository, and a stranger reporting a crash is exactly that.
        val url = GitHubIssueUrl.forNewIssue(report()).url

        assertFalse(url.contains("labels="))
        assertFalse(url.contains("assignees="))
        assertFalse(url.contains("milestone="))
        assertFalse(url.contains("projects="))
    }
}
