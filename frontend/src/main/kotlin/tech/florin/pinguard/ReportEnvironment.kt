package tech.florin.pinguard

/**
 * What the IDE was when the exception happened, as plain strings.
 *
 * Deliberately not a bag of platform objects: every fact is read once, at the
 * edge, so the issue text can be composed and tested without a running IDE.
 */
internal data class ReportEnvironment(
    val plugin: String,
    val ide: String,
    val build: String,
    val os: String,
    val jvm: String,
    val locale: String,
) {
    /**
     * The environment section of the issue body.
     *
     * English, and deliberately not routed through [PinGuardBundle]: this text is
     * addressed to the maintainer, and a localised bug report is a worse one.
     */
    fun asMarkdown(): String = listOf(
        "- Plugin: $plugin",
        "- IDE: $ide ($build)",
        "- OS: $os",
        "- JVM: $jvm",
        "- Locale: $locale",
    ).joinToString("\n")

    companion object {
        private const val UNKNOWN = "unknown"

        /**
         * [plugin] alone, with every fact about the IDE reported as unknown.
         *
         * Everything but the plugin's own coordinates is read from the running IDE,
         * and that read can fail. A report naming half the environment is worth far
         * more than no report at all.
         */
        fun unknown(plugin: String): ReportEnvironment = ReportEnvironment(
            plugin = plugin,
            ide = UNKNOWN,
            build = UNKNOWN,
            os = UNKNOWN,
            jvm = UNKNOWN,
            locale = UNKNOWN,
        )
    }
}
