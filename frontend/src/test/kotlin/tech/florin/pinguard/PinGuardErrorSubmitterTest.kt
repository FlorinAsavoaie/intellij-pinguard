package tech.florin.pinguard

import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import java.awt.Panel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The extension point class. Most of this covers one contract: the platform's
 * consumer is answered exactly once, whatever happens, because a report action
 * that never answers stays greyed out for the rest of the IDE session.
 */
class PinGuardErrorSubmitterTest {

    /** Records what it was asked to do, and in which order. */
    private class RecordingDelivery : ReportDelivery {
        val calls = mutableListOf<String>()
        var copied: String? = null
            private set
        var opened: String? = null
            private set

        override fun copyFullReport(text: String) {
            calls += "copy"
            copied = text
        }

        override fun openIssueForm(url: String) {
            calls += "open"
            opened = url
        }
    }

    private class ExplodingDelivery : ReportDelivery {
        override fun copyFullReport(text: String) = Unit
        override fun openIssueForm(url: String): Nothing = error("no browser here")
    }

    /** Counts answers, because both "never" and "twice" are bugs. */
    private class CountingConsumer : Consumer<SubmittedReportInfo> {
        var invocations = 0
            private set
        var last: SubmittedReportInfo? = null
            private set

        override fun consume(t: SubmittedReportInfo) {
            invocations++
            last = t
        }
    }

    private var describedPlugin: String? = null

    private fun submitter(delivery: ReportDelivery) = PinGuardErrorSubmitter(
        environment = { plugin ->
            describedPlugin = plugin
            ReportEnvironment.unknown(plugin)
        },
        delivery = delivery,
    )

    /**
     * An event with a short, fixed trace.
     *
     * The stack a test throwable picks up by itself is the whole JUnit runner, deep
     * enough to overrun the URL budget on its own — so a report built from one would
     * be truncated for a reason that has nothing to do with what is being asserted.
     */
    private fun event(
        message: String = "boom",
        throwable: Throwable = IllegalStateException("boom"),
    ): IdeaLoggingEvent {
        throwable.stackTrace = arrayOf(StackTraceElement("tech.florin.One", "a", "One.kt", 1))
        return IdeaLoggingEvent(message, throwable)
    }

    /** An event whose trace cannot possibly fit in a URL. */
    private fun hugeEvent(): IdeaLoggingEvent {
        val boom = IllegalStateException("outer")
        boom.stackTrace = Array(2_000) {
            StackTraceElement("tech.florin.One", "frame$it", "VeryLongFileName.kt", it)
        }
        return IdeaLoggingEvent("boom", boom)
    }

    private fun submit(
        submitter: PinGuardErrorSubmitter,
        consumer: CountingConsumer,
        events: Array<IdeaLoggingEvent> = arrayOf(event()),
        additionalInfo: String? = null,
    ) = submitter.submit(events, additionalInfo, Panel(), consumer)

    @Test
    fun `reporting the first event opens a prefilled issue form`() {
        val delivery = RecordingDelivery()

        submit(submitter(delivery), CountingConsumer())

        assertTrue(delivery.opened.orEmpty().startsWith("https://github.com/FlorinAsavoaie/intellij-pinguard/"))
    }

    @Test
    fun `reporting tells the platform it started, so the dialog stops offering to report`() {
        val consumer = CountingConsumer()

        val started = submit(submitter(RecordingDelivery()), consumer)

        assertTrue(started)
        assertEquals(1, consumer.invocations)
        assertEquals(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE, consumer.last?.status)
    }

    @Test
    fun `an event array with nothing in it is refused instead of pretending to report`() {
        val consumer = CountingConsumer()

        val started = submit(submitter(RecordingDelivery()), consumer, events = emptyArray())

        assertFalse(started)
        assertEquals(0, consumer.invocations, "the platform recovers by itself only when told false")
    }

    @Test
    fun `a delivery that blows up still answers the platform, so the report button is not wedged forever`() {
        val consumer = CountingConsumer()

        val started = submit(submitter(ExplodingDelivery()), consumer)

        assertTrue(started)
        assertEquals(1, consumer.invocations)
        assertEquals(SubmittedReportInfo.SubmissionStatus.FAILED, consumer.last?.status)
    }

    @Test
    fun `a submitter the platform never handed a descriptor still names the plugin`() {
        submit(submitter(RecordingDelivery()), CountingConsumer())

        assertEquals("tech.florin.pinguard unknown", describedPlugin)
    }

    @Test
    fun `what the user typed into the dialog is carried into the issue`() {
        val delivery = RecordingDelivery()

        submit(submitter(delivery), CountingConsumer(), additionalInfo = "it happened on Close All")

        assertTrue(delivery.opened.orEmpty().contains("Close+All"))
    }

    @Test
    fun `a null comment is reported as no comment at all`() {
        val delivery = RecordingDelivery()

        submit(submitter(delivery), CountingConsumer(), additionalInfo = null)

        assertFalse(delivery.opened.orEmpty().contains("What+happened"))
    }

    @Test
    fun `the button and the privacy notice are resolved from the bundle`() {
        // A key that is missing from the properties file resolves to "!key!" rather
        // than failing, so nothing but an assertion catches the typo.
        val submitter = submitter(RecordingDelivery())

        assertEquals("Report on GitHub", submitter.getReportActionText())
        assertTrue(submitter.getPrivacyNoticeText().contains("Nothing is sent"))
    }

    @Test
    fun `a report that fits is opened without touching the clipboard`() {
        val delivery = RecordingDelivery()

        submit(submitter(delivery), CountingConsumer())

        assertEquals(listOf("open"), delivery.calls)
        assertNull(delivery.copied)
    }

    @Test
    fun `a report that had to be cut leaves the whole of it on the clipboard, before the form opens`() {
        // The issue body tells the reader to paste, so the clipboard has to be
        // loaded by the time the form appears.
        val delivery = RecordingDelivery()

        submit(submitter(delivery), CountingConsumer(), events = arrayOf(hugeEvent()))

        assertEquals(listOf("copy", "open"), delivery.calls)
        assertTrue(
            delivery.copied.orEmpty().contains("frame1999"),
            "the clipboard copy is the one with nothing taken out of it",
        )
    }

    @Test
    fun `the url handed back to the platform is the one that was opened`() {
        val delivery = RecordingDelivery()
        val consumer = CountingConsumer()

        submit(submitter(delivery), consumer)

        assertEquals(delivery.opened, consumer.last?.url)
    }

    @Test
    fun `every event the dialog groups together reaches the issue, not just the first`() {
        // Reporting only events[0] silently drops the rest while telling the
        // consumer the whole batch was filed.
        val delivery = RecordingDelivery()
        val second = event("second", IllegalArgumentException("the second failure"))

        submit(submitter(delivery), CountingConsumer(), events = arrayOf(event(), second))

        assertTrue(
            delivery.opened.orEmpty().contains("IllegalArgumentException"),
            "the second event never made it into the report",
        )
    }

    @Test
    fun `a failed report carries no link for the dialog to offer`() {
        val consumer = CountingConsumer()

        submit(submitter(ExplodingDelivery()), consumer)

        assertNull(consumer.last?.url)
    }
}
