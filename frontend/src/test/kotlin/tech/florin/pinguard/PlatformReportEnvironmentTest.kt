package tech.florin.pinguard

import com.intellij.testFramework.junit5.TestApplication
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading the running IDE's own version details.
 *
 * The Plugin Verifier checks that every call in [PlatformReportEnvironment] still
 * *resolves* on the declared floor. What it cannot check is that they return
 * anything: a call that resolves and then throws lands in the catch-all, and every
 * bug report from that IDE arrives saying "unknown".
 */
@TestApplication
internal class PlatformReportEnvironmentTest {

    private val plugin = "tech.florin.pinguard 0.1.0"

    private val described = PlatformReportEnvironment.describe(plugin)

    @Test
    fun `the environment is really read, not quietly reported as unknown`() {
        assertNotEquals(
            ReportEnvironment.unknown(plugin),
            described,
            "every field fell back; one of the platform calls is throwing at runtime",
        )
    }

    @Test
    fun `the plugin's own coordinates are passed through untouched`() {
        assertEquals(plugin, described.plugin)
    }

    @Test
    fun `each field a maintainer needs carries something`() {
        assertTrue(described.ide.isNotBlank(), "ide")
        assertTrue(described.build.isNotBlank(), "build")
        assertTrue(described.os.isNotBlank(), "os")
        assertTrue(described.jvm.isNotBlank(), "jvm")
        assertTrue(described.locale.isNotBlank(), "locale")
    }

    @Test
    fun `the build is the IDE's build number rather than a repeat of its name`() {
        assertNotEquals(described.ide, described.build)
        assertNotEquals("unknown", described.build)
    }

    @Test
    fun `the jvm names both its version and its vendor`() {
        // The parenthesised vendor is what distinguishes a JBR bug from an ordinary
        // JDK one.
        assertTrue(described.jvm.contains("("), "no vendor in '${described.jvm}'")
        assertTrue(described.jvm.endsWith(")"))
    }

    @Test
    fun `the locale is a language tag rather than a java locale toString`() {
        // Locale.toString gives en_US; a report should carry en-US.
        assertFalse(described.locale.contains("_"), "'${described.locale}' looks like Locale.toString()")
    }

    @Test
    fun `the markdown block names every field`() {
        val markdown = described.asMarkdown()

        listOf("Plugin", "IDE", "OS", "JVM", "Locale").forEach { heading ->
            assertTrue(markdown.contains("- $heading:"), "the environment block has no '$heading' line")
        }
        assertTrue(markdown.contains(described.build), "the build number never reaches the issue")
    }
}
