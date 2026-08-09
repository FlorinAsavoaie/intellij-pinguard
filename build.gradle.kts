import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform")

    // Applied nowhere: this project keeps no changelog file for it to manage. It is
    // declared only to put `markdownToHTML` on the build script's classpath, which
    // is what turns a GitHub release body into the HTML the descriptor wants.
    // Applying it would also make `patchChangelog` a dependency of `publishPlugin`,
    // and that task rewrites a file that no longer exists.
    id("org.jetbrains.changelog") version "2.5.0" apply false
}

group = providers.gradleProperty("pluginGroup").get()

// A release is a git tag and the GitHub release built from it; release.yml passes
// that tag's version in through PLUGIN_VERSION, and `-PpluginVersion=` says the same
// to a local build. Everything else is the placeholder.
//
// `0.0.0` rather than a SNAPSHOT or `-dev` suffix, because a pre-release identifier
// is what picks the Marketplace channel below.
val placeholderVersion = "0.0.0"

// One rule for the channel below, the draft-release task, and release.yml's check on
// the tag. release.yml keeps a copy in bash because it runs before Gradle does.
val versionFormat = Regex("""\d{1,9}\.\d{1,9}\.\d{1,9}(-(alpha|beta|eap|rc)(\.\d{1,9})?)?""")

// Blanks are filtered out rather than left to `orElse`, which only supplies a value
// that is *absent*: `-PpluginVersion=` with nothing after it is present and empty —
// an unset shell variable expanded into a release command — and would otherwise
// publish as no `<version>` element at all, to the stable channel, past both guards
// at the bottom of this file.
val pluginVersion = providers.gradleProperty("pluginVersion").filter(String::isNotBlank)
    .orElse(providers.environmentVariable("PLUGIN_VERSION").filter(String::isNotBlank))
    .orElse(placeholderVersion)
    .map { candidate ->
        require(candidate == placeholderVersion || versionFormat.matches(candidate)) {
            "'$candidate' is not a version this plugin can publish: expected X.Y.Z, optionally " +
                "followed by -alpha, -beta, -eap or -rc and a number."
        }
        candidate
    }

version = pluginVersion.get()

// Where release.yml writes the GitHub release body for the build to pick up. Under
// `build/` because it is produced per run and belongs to no commit — which also
// means `clean` throws it away, so a release job must never run one.
val releaseNotesFile = layout.buildDirectory.file("release-notes.md")

// Marketplace creates a channel the first time an upload names one, so a mistyped
// identifier would not fail — it would quietly open a channel nobody is subscribed
// to. `versionFormat` above is what prevents that, by admitting only four.
fun channelFor(version: String): String =
    version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }

// This project holds no code at all: everything lives in `frontend`, tests
// included, and all this one contributes is META-INF/plugin.xml and the packaging.
subprojects {
    apply(plugin = "org.jetbrains.intellij.platform.module")
    apply(plugin = "org.jetbrains.kotlin.jvm")
}

allprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)

            explicitApi()

            compilerOptions {
                allWarningsAsErrors.set(true)
                extraWarnings.set(true)
                freeCompilerArgs.add("-Xjsr305=strict")

                // Pinned to the Kotlin the oldest supported platform bundles — 2.2.20
                // on the 253 floor — because `kotlin.stdlib.default.dependency =
                // false` runs this plugin on the IDE's stdlib rather than its own.
                // Compiling against a newer API, or emitting metadata the IDE's
                // kotlin-reflect cannot read, only shows up at runtime on the oldest
                // IDE anyone runs it on.
                //
                // `progressiveMode` is absent because it opts into the *latest*
                // language semantics, and the compiler rejects the combination.
                apiVersion.set(KotlinVersion.KOTLIN_2_2)
                languageVersion.set(KotlinVersion.KOTLIN_2_2)
            }
        }
    }
}

dependencies {
    intellijPlatform {
        // `intellijIdea` rather than `intellijIdeaCommunity`: IDEA stopped being
        // published as separate Community and Ultimate distributions in 2025.3,
        // which is the floor this plugin now declares.
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Moves frontend.jar into the plugin's lib/modules, which is what makes
        // it a content module the platform can load on its own — in the frontend
        // process, where the editor tabs actually are.
        pluginModule(implementation(project(":frontend")))
    }
}

intellijPlatform {
    pluginConfiguration {
        // No `version` here: it defaults to the project's, which is resolved at the
        // top of this file.

        // The change notes users read in the IDE are the body of the GitHub release,
        // which release.yml drops into this file before it builds anything. A build
        // with no file is a development build; the check on `patchPluginXml` at the
        // bottom of this file stops a real release from shipping that placeholder.
        //
        // The rendered HTML lands inside a CDATA section, which `]]>` would close
        // early. Markdown prose cannot produce that sequence, but raw HTML in the
        // release body passes through verbatim.
        //
        // Marketplace documents change-notes as accepting only "simple HTML
        // elements", so release bodies stay clear of tables, images and <details>.
        changeNotes = providers.fileContents(releaseNotesFile).asText
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { markdownToHTML(it).replace("]]>", "]]&gt;") }
            .orElse("Development build, not a release.")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    publishing {
        // The channel is the version's pre-release identifier: 0.2.0 goes to
        // `default`, the stable repository every Marketplace user sees, while
        // 0.2.0-beta.1 goes to a `beta` channel only subscribers receive. Derived
        // rather than hardcoded, which would have to be edited in step with every
        // release in turn — and the once that is forgotten, a beta ships to everyone.
        //
        // No `token` here on purpose: it already defaults to the PUBLISH_TOKEN
        // environment variable, which is how release.yml supplies it.
        channels = pluginVersion.map { listOf(channelFor(it)) }
    }

    // Off because the 2025.3 distribution the unified `idea` artifact resolves to
    // ships several bundled plugins as empty directories (station-plugin, webp and
    // others have a lib/ with no jars), so the headless IDE this task starts logs
    // SEVERE for every extension it cannot instantiate and exits non-zero before it
    // reaches us. PinGuard itself loads clean in that same run. Worth turning back
    // on when the distribution is whole again.
    buildSearchableOptions = false

    // Split mode is deliberately *not* configured here. `splitMode` and
    // `pluginInstallationTarget` are conventions for every SplitModeAware task,
    // `runIde` and the test sandboxes included, so setting them would turn plain
    // `./gradlew runIde` into a backend plus a JetBrains Client. `runIdeSplitMode`
    // forces split mode through its own registration and needs none of it.

    pluginVerification {
        ides {
            // Derived from the declared compatibility range rather than written out
            // build by build. `untilBuild` is null, so Marketplace offers the plugin
            // to every IDE from the 253 floor onwards, and a hand-written pair of
            // versions stops covering that range the moment the next major ships —
            // which, for a plugin built on `@ApiStatus.Experimental` APIs, is exactly
            // the release that would have broken it.
            //
            // RELEASE only, not the RELEASE + EAP + RC of `recommended()`: the
            // pre-release channels resolve to builds that are superseded and
            // withdrawn as a major stabilises, so they fail releases for reasons that
            // are none of this plugin's doing. IntelliJ IDEA only, because that is
            // the compatibility README.md claims has been measured.
            select {
                channels = listOf(ProductRelease.Channel.RELEASE)
                types = listOf(IntelliJPlatformType.IntellijIdea)
            }
        }
    }
}

// A version was asked for, so this is a release, so it needs notes: the fallback
// text that keeps development builds working must never reach Marketplace.
//
// On `patchPluginXml` rather than on `publishPlugin` so it fires early — which also
// means `./gradlew test -PpluginVersion=1.2.3` fails here, the frontend module's
// test classpath taking the patched descriptor.
tasks.named("patchPluginXml") {
    val notes = releaseNotesFile
    val versionInHand = version.toString()
    val placeholder = placeholderVersion
    doFirst {
        if (versionInHand != placeholder) {
            val file = notes.get().asFile
            require(file.isFile && file.readText().isNotBlank()) {
                "Refusing to build $versionInHand with no change notes: expected the release " +
                    "body in ${file.path}. release.yml writes it; locally, write it by hand."
            }
        }
    }
}

// Marketplace takes the placeholder without complaint and then never shows it:
// SemVer ordering sorts `0.0.0` below every real release and off the plugin page. An
// upload cannot be taken back, only hidden, so the only useful place to notice is
// before it leaves.
//
// The second check is about which archive goes: `publishPlugin` picks the signed one
// only when `signPlugin` *executed* in this same invocation, since it reads that
// task's `didWork`. Signing in one command and publishing in the next uploads the
// unsigned archive, silently.
tasks.named<PublishPluginTask>("publishPlugin") {
    val versionToPublish = version.toString()
    val placeholder = placeholderVersion
    val fileToUpload = archiveFile
    doFirst {
        require(versionToPublish != placeholder) {
            "Refusing to publish the placeholder version $placeholder. Pass the release " +
                "version in through PLUGIN_VERSION or -PpluginVersion=<x.y.z>."
        }
        val name = fileToUpload.get().asFile.name
        require(name.endsWith("-signed.zip")) {
            "Refusing to publish $name: it is the unsigned archive. Either the signing " +
                "credentials are absent, or signPlugin ran in an earlier Gradle invocation " +
                "and this one found it up to date. Sign and publish in one command."
        }
    }
}

// Checks everything that has to be true before a release, then cuts the draft.
// Until the draft is published there is no tag, no Marketplace upload and nothing to
// retract, so this is the last point at which a mistake costs nothing. Publishing is
// left to a person: a release published by a token starts no workflow at all.
abstract class ReleaseDraftTask : DefaultTask() {

    @get:Input
    @get:Optional
    @get:Option(option = "release-version", description = "Version to release, without the leading v.")
    abstract val releaseVersion: Property<String>

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun createDraft() {
        val version = releaseVersion.orNull
            ?: fail("No version given. Use ./gradlew releaseDraft --release-version=1.2.3")

        require(VERSION.matches(version)) {
            "'$version' is not a version this project can publish: expected X.Y.Z, optionally " +
                "followed by -alpha, -beta, -eap or -rc and a number."
        }
        require(version != PLACEHOLDER) { "$PLACEHOLDER is the placeholder for builds that are not releases." }

        requireTool("gh", "https://cli.github.com")
        requireTool("git", "https://git-scm.com")
        checkSucceeds("gh", "auth", "status") {
            "gh is not authenticated, so its answers about what already exists cannot be trusted. Run gh auth login."
        }

        check(git("status", "--porcelain", "--untracked-files=no").isEmpty()) {
            "There are uncommitted changes to tracked files. A release is cut from what is pushed."
        }
        checkSucceeds("git", "remote", "get-url", "origin") { "This repository has no 'origin' remote." }

        // Pruned, or a stale `refs/remotes/origin/<branch>` left behind by a branch
        // deleted at origin would vouch for a commit origin no longer references.
        checkSucceeds("git", "fetch", "--prune", "origin", "--tags", "--quiet") { "Could not fetch from origin." }

        val head = git("rev-parse", "HEAD")
        check(git("branch", "--remotes", "--list", "origin/*", "--contains", head).isNotEmpty()) {
            "HEAD is on no branch at origin. Push it first, so the release names a commit that exists there."
        }

        val tag = "v$version"
        check(git("tag", "--list", tag).isEmpty()) { "Tag $tag already exists." }
        check(git("ls-remote", "--tags", "origin", "refs/tags/$tag").isEmpty()) { "Tag $tag already exists at origin." }
        check(run("gh", "release", "view", tag).first != 0) {
            "A release or draft for $tag already exists. Delete it, or pick another version."
        }

        val existing = (git("tag", "--list", "v*").lines() + gh("release", "list", "--limit", "1000", "--json", "tagName", "--jq", ".[].tagName").lines())
            .map(String::trim)
            .filter { it.startsWith("v") && VERSION.matches(it.removePrefix("v")) }
            .map { it.removePrefix("v") }
            .distinct()
        val highest = existing.maxWithOrNull(BY_VERSION)
        check(highest == null || BY_VERSION.compare(version, highest) > 0) {
            "Version $version is not newer than $highest, which is already released. Marketplace orders by " +
                "version and shows the newest, so an older one uploads and is never seen."
        }

        // So GitHub's own label agrees with the Marketplace channel the version picks.
        val preRelease = if (version.contains("-")) listOf("--prerelease") else emptyList()
        checkSucceeds(
            *(listOf("gh", "release", "create", tag, "--draft", "--target", head, "--title", tag, "--generate-notes") + preRelease)
                .toTypedArray(),
        ) { "Could not create the draft release." }

        logger.lifecycle(
            "Draft release $tag created at $head.\n" +
                "Replace the generated notes with what users should read, then publish it: " +
                "publishing is what creates the tag and starts the release workflow.",
        )
    }

    private fun git(vararg arguments: String): String = tool("git", *arguments)

    private fun gh(vararg arguments: String): String = tool("gh", *arguments)

    private fun tool(command: String, vararg arguments: String): String {
        val (exitValue, output) = run(command, *arguments)
        check(exitValue == 0) { "$command ${arguments.joinToString(" ")} failed: $output" }
        return output
    }

    // A command that cannot be started at all throws out of `exec` rather than
    // returning a status, hence [NOT_STARTED].
    private fun run(vararg command: String): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val diagnostics = ByteArrayOutputStream()
        return try {
            val result = execOperations.exec {
                commandLine(*command)
                workingDir = repositoryRoot.get().asFile
                standardOutput = output
                errorOutput = diagnostics
                isIgnoreExitValue = true
            }
            // Only stdout is the value: git writes advice and warnings to stderr, and
            // a caller parsing a commit SHA out of a merged stream would take those
            // for part of the answer.
            val text = if (result.exitValue == 0) output.toString() else output.toString() + diagnostics
            result.exitValue to text.trim()
        } catch (failure: Exception) {
            NOT_STARTED to generateSequence(failure as Throwable) { it.cause }
                .mapNotNull { it.message }
                .joinToString(": ")
                .ifEmpty { "could not be started" }
        }
    }

    private fun requireTool(tool: String, where: String) {
        val (exitValue, output) = run(tool, "--version")
        check(exitValue == 0) {
            "$tool is needed to cut a release and could not be run. Install it from $where, " +
                "and if it is installed already, run ./gradlew --stop so the daemon picks up your PATH.\n  $output"
        }
    }

    private fun checkSucceeds(vararg command: String, message: () -> String) {
        val (exitValue, output) = run(*command)
        check(exitValue == 0) { "${message()}\n  ${command.joinToString(" ")}\n  $output" }
    }

    private fun fail(message: String): Nothing = throw GradleException(message)

    private companion object {
        const val NOT_STARTED = -1
        const val PLACEHOLDER = "0.0.0"
        val VERSION = Regex("""\d+\.\d+\.\d+(-(alpha|beta|eap|rc)(\.\d+)?)?""")

        val BY_VERSION: Comparator<String> = Comparator { left, right ->
            ordering(left).zip(ordering(right))
                .firstNotNullOfOrNull { (a, b) -> a.compareTo(b).takeIf { it != 0 } }
                ?: 0
        }

        // Sorts the way SemVer orders a pre-release: below the release it leads to,
        // and alphabetically among themselves — which is the order the four
        // identifiers already read in.
        fun ordering(version: String): List<Int> {
            val (release, preRelease) = version.split("-", limit = 2).let { it[0] to it.getOrNull(1) }
            val numbers = release.split(".").map(String::toInt)
            val identifier = preRelease?.substringBefore(".")
            val rank = listOf("alpha", "beta", "eap", "rc").indexOf(identifier)
            val iteration = preRelease?.substringAfter(".", "0")?.toIntOrNull() ?: 0
            return numbers + listOf(if (identifier == null) Int.MAX_VALUE else rank, iteration)
        }
    }
}

tasks.register<ReleaseDraftTask>("releaseDraft") {
    group = "publishing"
    description = "Creates a draft GitHub release for a version, after checking it is safe to."
    repositoryRoot.set(layout.projectDirectory)
}
