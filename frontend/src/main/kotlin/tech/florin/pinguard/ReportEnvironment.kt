package tech.florin.pinguard

/**
 * What the IDE was when the exception happened, as plain strings.
 *
 * Plain strings and not platform objects, so the issue text can be composed
 * without a running IDE.
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
     * English, and deliberately not routed through [PinGuardBundle]: it is
     * addressed to the maintainer, not to the user.
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

        /** [plugin] alone, with every fact about the IDE reported as unknown. */
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
