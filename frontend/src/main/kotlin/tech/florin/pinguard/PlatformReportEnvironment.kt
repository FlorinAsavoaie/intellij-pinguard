package tech.florin.pinguard

import com.intellij.DynamicBundle
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.OS

private val LOG: Logger = Logger.getInstance(PlatformReportEnvironment::class.java)

/** Reads the running IDE's own version details. */
internal object PlatformReportEnvironment {

    /**
     * Describes the IDE this plugin is running in, falling back to
     * [ReportEnvironment.unknown] if any of it cannot be read.
     */
    fun describe(plugin: String): ReportEnvironment =
        try {
            val appInfo = ApplicationInfo.getInstance()
            ReportEnvironment(
                plugin = plugin,
                ide = appInfo.fullApplicationName,
                build = appInfo.build.asString(),
                // Not SystemInfo.getOsNameAndVersion(), which is deprecated on the
                // 2025.3 floor and so would not compile here; and version() rather
                // than the version field beside it, deprecated in turn.
                os = "${OS.CURRENT} ${OS.CURRENT.version()}",
                jvm = "${SystemInfo.JAVA_RUNTIME_VERSION} (${SystemInfo.JAVA_VENDOR})",
                // Not Locale.getDefault(): with a language pack installed, the
                // locale the user sees is not the one the JVM reports.
                locale = DynamicBundle.getLocale().toLanguageTag(),
            )
        } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            LOG.warn("could not read the ide's own version details; reporting them as unknown", failure)
            ReportEnvironment.unknown(plugin)
        }
}
