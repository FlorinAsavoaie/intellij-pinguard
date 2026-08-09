package tech.florin.pinguard

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The trace reader. What matters is that shortening a trace never costs a
 * `Caused by:` header, where a wrapped exception keeps its root cause.
 */
class StackTraceDigestTest {

    private fun trace(vararg lines: String): String = lines.joinToString("\n")

    private val wrapped = trace(
        "java.lang.IllegalStateException: outer",
        "\tat tech.florin.One.a(One.kt:11)",
        "\tat tech.florin.One.b(One.kt:12)",
        "Caused by: java.lang.RuntimeException: middle",
        "\tat tech.florin.Two.a(Two.kt:21)",
        "\tat tech.florin.Two.b(Two.kt:22)",
        "Caused by: java.io.IOException: the actual problem",
        "\tat tech.florin.Three.a(Three.kt:31)",
        "\tat tech.florin.Three.b(Three.kt:32)",
    )

    @Test
    fun `an empty trace has no cause blocks`() {
        assertEquals(emptyList(), StackTraceDigest.blocks(""))
    }

    @Test
    fun `a blank trace has no cause blocks`() {
        assertEquals(emptyList(), StackTraceDigest.blocks("  \n\t\n  "))
    }

    @Test
    fun `a trace with nothing wrapped is a single block`() {
        val blocks = StackTraceDigest.blocks(
            trace("java.lang.RuntimeException: alone", "\tat tech.florin.One.a(One.kt:1)"),
        )

        assertEquals(1, blocks.size)
        assertEquals("java.lang.RuntimeException: alone", blocks.single().header)
        assertEquals(listOf("\tat tech.florin.One.a(One.kt:1)"), blocks.single().frames)
    }

    @Test
    fun `each Caused by line starts a new block`() {
        val blocks = StackTraceDigest.blocks(wrapped)

        assertEquals(3, blocks.size)
        assertEquals("java.lang.IllegalStateException: outer", blocks[0].header)
        assertEquals("Caused by: java.lang.RuntimeException: middle", blocks[1].header)
        assertEquals("Caused by: java.io.IOException: the actual problem", blocks[2].header)
        assertTrue(blocks.all { it.frames.size == 2 })
    }

    @Test
    fun `an indented Suppressed line starts a block too`() {
        val blocks = StackTraceDigest.blocks(
            trace(
                "java.lang.RuntimeException: outer",
                "\tat tech.florin.One.a(One.kt:1)",
                "\tSuppressed: java.io.IOException: while closing",
                "\t\tat tech.florin.Two.a(Two.kt:1)",
            ),
        )

        assertEquals(2, blocks.size)
        assertEquals("\tSuppressed: java.io.IOException: while closing", blocks[1].header)
    }

    @Test
    fun `rendering with a generous quota reproduces the trace it parsed`() {
        assertEquals(wrapped, StackTraceDigest.render(StackTraceDigest.blocks(wrapped), framesPerBlock = 500))
    }

    @Test
    fun `a windows trace is normalised to single newlines`() {
        val windows = "java.lang.RuntimeException: outer\r\n\tat tech.florin.One.a(One.kt:1)\r\n"

        assertEquals(
            "java.lang.RuntimeException: outer\n\tat tech.florin.One.a(One.kt:1)",
            StackTraceDigest.normalize(windows),
        )
    }

    @Test
    fun `a quota keeps the first frames of a block and counts the ones it dropped`() {
        val rendered = StackTraceDigest.render(StackTraceDigest.blocks(wrapped), framesPerBlock = 1)

        assertTrue(rendered.contains("\tat tech.florin.One.a(One.kt:11)"))
        assertTrue(rendered.contains("\tat tech.florin.Three.a(Three.kt:31)"))
        assertTrue(rendered.contains("... 1 frame omitted ..."))
    }

    @Test
    fun `the first and last blocks keep more frames than the wrappers between them`() {
        val deep = StackTraceDigest.blocks(
            trace(
                "java.lang.RuntimeException: outer",
                *Array(20) { "\tat tech.florin.One.f$it(One.kt:$it)" },
                "Caused by: java.lang.RuntimeException: middle",
                *Array(20) { "\tat tech.florin.Two.f$it(Two.kt:$it)" },
                "Caused by: java.io.IOException: root",
                *Array(20) { "\tat tech.florin.Three.f$it(Three.kt:$it)" },
            ),
        )

        val rendered = StackTraceDigest.render(deep, framesPerBlock = 10).split("\n")

        assertEquals(10, rendered.count { it.contains("tech.florin.One.") })
        assertEquals(3, rendered.count { it.contains("tech.florin.Two.") }, "35% of a quota of 10")
        assertEquals(10, rendered.count { it.contains("tech.florin.Three.") })
    }

    @Test
    fun `every block header survives the tightest quota, because the root cause lives in one`() {
        val rendered = StackTraceDigest.render(StackTraceDigest.blocks(wrapped), framesPerBlock = 0)

        assertTrue(rendered.contains("java.lang.IllegalStateException: outer"))
        assertTrue(rendered.contains("Caused by: java.lang.RuntimeException: middle"))
        assertTrue(
            rendered.contains("Caused by: java.io.IOException: the actual problem"),
            "the last block is the root cause; losing it is losing the report",
        )
    }

    @Test
    fun `a quota of zero keeps the headers and nothing else`() {
        val rendered = StackTraceDigest.render(StackTraceDigest.blocks(wrapped), framesPerBlock = 0)

        assertEquals(0, rendered.split("\n").count { it.contains("\tat ") })
    }

    @Test
    fun `an absurd quota does not overflow the wrapper arithmetic`() {
        val rendered = StackTraceDigest.render(StackTraceDigest.blocks(wrapped), framesPerBlock = Int.MAX_VALUE)

        assertEquals(wrapped, rendered)
    }

    @Test
    fun `the fence is always longer than the longest run of backticks in the trace`() {
        assertEquals("````", StackTraceDigest.fenceFor("boom ``` inside"))
        assertEquals("``````", StackTraceDigest.fenceFor("boom ````` inside"))
    }

    @Test
    fun `a trace with no backticks gets the ordinary three-backtick fence`() {
        assertEquals("```", StackTraceDigest.fenceFor(wrapped))
    }

    @Test
    fun `the first line of a blank trace is empty rather than absent`() {
        assertEquals("", StackTraceDigest.firstLine("   \n  "))
        assertEquals("java.lang.IllegalStateException: outer", StackTraceDigest.firstLine(wrapped))
    }
}
