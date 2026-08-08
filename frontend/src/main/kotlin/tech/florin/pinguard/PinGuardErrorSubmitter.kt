package tech.florin.pinguard

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.util.Consumer
import java.awt.Component
import org.jetbrains.annotations.NonNls

private val LOG: Logger = Logger.getInstance(PinGuardErrorSubmitter::class.java)

/** Matches `<id>` in `plugin.xml`; used only if the platform never injected a descriptor. */
@NonNls
private const val PLUGIN_ID = "tech.florin.pinguard"

/**
 * How a prepared issue reaches the user.
 *
 * The one seam in this file, because the ordering — clipboard first, browser
 * second — has to be assertable without a desktop.
 */
internal interface ReportDelivery {
    /** Puts the whole report on the clipboard, for the user to paste into the issue. */
    fun copyFullReport(text: String)

    /** Opens the prefilled issue form in the user's browser. */
    fun openIssueForm(url: String)
}

/** The real browser and the real clipboard. */
internal object PlatformReportDelivery : ReportDelivery {

    /**
     * Losing the paste is a worse report rather than a failed one, so a clipboard
     * that refuses is logged and the form still opens.
     */
    override fun copyFullReport(text: String) {
        try {
            CopyPasteManager.copyTextToClipboard(text)
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not put the full report on the clipboard; the form keeps the shortened one", failure)
        }
    }

    /**
     * Takes [url] as-is. Routing it through `java.net.URI` would re-encode every
     * `%` as `%25` and hand GitHub a body of escape sequences.
     *
     * Reports nothing back: `BrowserUtil` turns a missing browser or a failed
     * launch into a notification of its own and returns normally, so there is no
     * success to check for here.
     */
    override fun openIssueForm(url: String) {
        BrowserUtil.browse(url)
    }
}

/**
 * Puts a **Report on GitHub** button on the IDE's error dialog for exceptions the
 * platform attributes to PinGuard.
 *
 * Nothing is transmitted from the IDE. The report is composed locally and opened
 * as a prefilled issue form in the user's browser, which they then read and submit
 * themselves — the same posture as the rest of the plugin, which keeps its
 * diagnostics in `idea.log` rather than sending them anywhere.
 *
 * Three details of the platform contract are load-bearing here:
 *
 * - **[submit] runs synchronously on the EDT.** That is tolerable only because
 *   nothing on this path blocks: `BrowserUtil.browse` hands the launch to a
 *   coroutine on the IO dispatcher, and everything else is bounded string work
 *   over at most a few kilobytes of output.
 * - **The consumer must be called exactly once, on every path.** Returning true
 *   without calling it leaves the platform's message marked as submitting forever,
 *   and the Report button stays greyed out for that error for the rest of the IDE
 *   session — so the failure path reports `FAILED` rather than returning early.
 * - **`NEW_ISSUE` means the flow reached GitHub, not that an issue exists.** The
 *   platform has no status for a hand-off; `FAILED` would grey the button out just
 *   the same while mislabelling a working report.
 *
 * `IdeaLoggingEvent.getData()` is left alone: it is a platform internal.
 *
 * Public because the module descriptor names it and the platform instantiates it
 * reflectively.
 */
public class PinGuardErrorSubmitter internal constructor(
    private val environment: (plugin: String) -> ReportEnvironment,
    private val delivery: ReportDelivery,
) : ErrorReportSubmitter() {

    /** Used by the platform when instantiating the extension. */
    @Suppress("unused")
    public constructor() : this(
        environment = PlatformReportEnvironment::describe,
        delivery = PlatformReportDelivery,
    )

    override fun getReportActionText(): String = PinGuardBundle.message("error.report.action")

    override fun getPrivacyNoticeText(): String = PinGuardBundle.message("error.privacy.notice")

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        if (events.isEmpty()) return false

        val info = try {
            val url = handOver(
                IssueReport(
                    message = events.first().message,
                    // Every event, not just the first: the dialog groups related
                    // failures into one array, and reporting one of them while
                    // telling the platform the batch was filed loses the rest with
                    // nothing to show it happened.
                    stackTrace = events.joinToString("\n\n") { it.throwableText },
                    userComment = additionalInfo,
                    environment = environment(pluginCoordinates()),
                ),
            )
            SubmittedReportInfo(
                url,
                PinGuardBundle.message("error.report.link"),
                SubmittedReportInfo.SubmissionStatus.NEW_ISSUE,
            )
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not open a github issue form; the report was not handed over", failure)
            SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED)
        }

        consumer.consume(info)
        return true
    }

    /**
     * Hands a prepared issue to the user's browser.
     *
     * When the report had to be shortened to fit a URL the whole of it goes on the
     * clipboard first, because the issue body tells the reader to paste it and the
     * paste has to be ready by the time the form appears.
     *
     * @return the URL that was opened, so it can be offered back as a link.
     */
    private fun handOver(report: IssueReport): String {
        val prepared = GitHubIssueUrl.forNewIssue(report)

        if (prepared.truncated) {
            delivery.copyFullReport(prepared.fullReport)
        }
        delivery.openIssueForm(prepared.url)

        // "It opened a form I did not fill in" is the report a maintainer gets when
        // this goes wrong; these three numbers distinguish the cases without asking
        // the user to reproduce anything.
        LOG.info(
            "opened a github issue form (url=${prepared.url.length} bytes, " +
                "trace=${report.stackTrace.length} chars, truncated=${prepared.truncated})",
        )
        return prepared.url
    }

    /**
     * The descriptor the platform injected, kept here rather than read back from
     * [getPluginDescriptor].
     *
     * 2025.3 marks the whole `ErrorReportSubmitter` surface `@ApiStatus.OverrideOnly`
     * — including, on the 2025.3 release build, the getter, which the Plugin
     * Verifier then reports against this plugin. Later builds no longer annotate
     * it, but the declared floor is the release build. Taking the value on the way
     * in is what a subclass is meant to do anyway.
     */
    private var descriptor: PluginDescriptor? = null

    override fun setPluginDescriptor(plugin: PluginDescriptor) {
        super.setPluginDescriptor(plugin)
        descriptor = plugin
    }

    /**
     * The plugin's id and version, as the issue names them.
     *
     * The descriptor arrives only in a running IDE, so it is absent everywhere
     * else. Neither it nor its version carries a nullability annotation, which
     * makes both silent NPE sites in Kotlin.
     */
    private fun pluginCoordinates(): String =
        "${descriptor?.pluginId?.idString ?: PLUGIN_ID} ${descriptor?.version ?: "unknown"}"
}
