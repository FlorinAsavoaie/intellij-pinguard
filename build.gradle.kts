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

// The version a release ships under is not written down anywhere in this
// repository. A release is a git tag and the GitHub release built from it, and
// release.yml passes that tag's version in through PLUGIN_VERSION;
// `-PpluginVersion=` says the same thing to a local build that needs a real
// version, which is what the first manual upload to Marketplace needs. Everything
// else — every development build, every pull request — is the placeholder.
//
// It is `0.0.0` rather than a SNAPSHOT or a `-dev` suffix because the pre-release
// identifier of the version in hand is what picks the Marketplace channel below,
// and a placeholder that carries one would be asking for a channel named after it.
val placeholderVersion = "0.0.0"

// One rule for what a version may look like, for everything that has an opinion
// about it: the channel below, the task that cuts a draft release, and release.yml's
// check on the tag. release.yml keeps a copy of this in bash because it runs before
// Gradle does, and the two are meant to say the same thing.
val versionFormat = Regex("""\d{1,9}\.\d{1,9}\.\d{1,9}(-(alpha|beta|eap|rc)(\.\d{1,9})?)?""")

// `orElse` supplies a value that is *absent*, not one that is present and empty, and
// `-PpluginVersion=` with nothing after it is the second of those — an unset shell
// variable expanded into a release command. Blanks are filtered out so they fall
// through to the placeholder instead of becoming a version in their own right, which
// publishes as no `<version>` element at all, to the stable channel, past both of the
// guards at the bottom of this file.
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
// pre-release identifier would not fail: it would quietly open a channel nobody is
// subscribed to and the release would never be seen again. What prevents that is
// `versionFormat` above, which admits only the four identifiers this project uses —
// so by the time a version reaches here, its channel is whichever of them it carries.
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

                // Pinned to the Kotlin the oldest supported platform bundles —
                // 2.2.20 on the 253 floor — because `kotlin.stdlib.default.dependency
                // = false` means this plugin runs on the IDE's stdlib rather than
                // one of its own. Compiling against a newer API, or emitting newer
                // metadata than the IDE's kotlin-reflect can read, is only
                // discoverable at runtime on the oldest IDE anyone runs it on.
                //
                // `progressiveMode` is deliberately absent: it opts into the
                // *latest* language semantics, which is precisely what pinning
                // languageVersion rules out, and the compiler rejects the
                // combination outright.
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
        // written once where it is published rather than kept in step with a second
        // copy in the repository. release.yml drops that body into this file before
        // it builds anything; a build with no file is a development build and says
        // so, and the check on `patchPluginXml` at the bottom of this file is what
        // stops a real release from quietly shipping that same placeholder.
        //
        // The rendered HTML goes into the descriptor inside a CDATA section, which
        // `]]>` would close early and leave the descriptor malformed. Markdown prose
        // cannot produce that sequence — it escapes `>` — but raw HTML in the body is
        // passed through verbatim, so the one sequence that matters is neutralised
        // here rather than left to whoever writes the release.
        //
        // Marketplace documents change-notes as accepting "simple HTML elements" —
        // headings, paragraphs, lists, links, emphasis, inline code. Markdown that
        // renders to a table, an image or a <details> block is outside what is
        // documented, so release bodies stay within the simple set.
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
        // The channel is the version's pre-release identifier: 0.2.0 has none and
        // goes to `default`, the stable repository every Marketplace user sees, while
        // 0.2.0-beta.1 goes to a `beta` channel only the people who added its URL to
        // their plugin repositories receive. Derived rather than written down because
        // the default is `default` either way, so a hardcoded channel has to be
        // edited in step with every release and pre-release in turn, and the one time
        // that is forgotten a beta ships to everyone.
        //
        // No `token` here on purpose: it already defaults to the PUBLISH_TOKEN
        // environment variable, which is how release.yml supplies it.
        channels = pluginVersion.map { listOf(channelFor(it)) }
    }

    // Off, reluctantly, and not because PinGuard has nothing to index — its
    // settings page is exactly what this task exists for. The 2025.3 distribution
    // the unified `idea` artifact resolves to ships several bundled plugins as
    // empty directories (station-plugin, webp and others have a lib/ with no
    // jars), so the headless IDE this task starts logs SEVERE for every extension
    // it then cannot instantiate and exits non-zero before it reaches us. Nothing
    // here is PinGuard's: the plugin itself loads clean in that same run. Worth
    // turning back on when the distribution is whole again.
    buildSearchableOptions = false

    // Split mode is deliberately *not* configured here. `splitMode` and
    // `pluginInstallationTarget` on this extension are conventions for every
    // SplitModeAware task, `runIde` and the test sandboxes included — setting them
    // turns plain `./gradlew runIde` into a backend plus a JetBrains Client, which
    // is not what anyone typing it expects and not what CONTRIBUTING.md documents.
    //
    // `runIdeSplitMode` needs none of it: its own registration forces split mode
    // and wires a backend and a frontend sandbox with the right installation
    // target on each side. It remains the way to see the frontend module load
    // where it matters.

    pluginVerification {
        ides {
            // The verified set is derived from the declared compatibility range
            // rather than written out build by build. `untilBuild` is null, so
            // Marketplace offers the plugin to every IDE from the 253 floor
            // onwards, and a hand-written pair of versions silently stops
            // covering that range the moment the next major ships — which, for a
            // plugin built on `@ApiStatus.Experimental` platform APIs, is exactly
            // the release that would have broken it. `select` resolves the range
            // afresh on every run instead.
            //
            // RELEASE only, and not the RELEASE + EAP + RC that `recommended()`
            // defaults to: the pre-release channels resolve to builds that are
            // superseded and withdrawn as a major stabilises, so they can fail a
            // release for reasons that are none of this plugin's doing. The type
            // stays IntelliJ IDEA because that is precisely the compatibility
            // README.md claims has been measured; the other IDEs are a separate
            // promise, and making it is a separate decision.
            select {
                channels = listOf(ProductRelease.Channel.RELEASE)
                types = listOf(IntelliJPlatformType.IntellijIdea)
            }
        }
    }
}

// A version was asked for, so this is a release, so it needs notes: the fallback
// text that keeps development builds working is exactly what must never reach
// Marketplace. Checked on `patchPluginXml`, the task that renders the descriptor,
// which means it is also reached by `test` — the frontend module's test classpath
// takes the patched descriptor — so a bare `./gradlew test -PpluginVersion=1.2.3`
// fails here too. That is the same demand made in a less obvious place, not a
// separate rule.
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
// SemVer ordering is on for new plugins, so `0.0.0` is accepted, sorted below every
// real release and left off the plugin page. The upload cannot be taken back, only
// hidden, so the only useful place to notice is before it leaves.
//
// The second check is about which archive is going: `publishPlugin` picks the signed
// one only when `signPlugin` *executed* in this same invocation, because it reads
// that task's `didWork`. Run `./gradlew signPlugin` in one command and
// `./gradlew publishPlugin` in the next and the second sees an up-to-date signing
// task, concludes nothing was signed, and uploads the unsigned archive — silently,
// and to a place it cannot be recalled from. Asserting on the name of the file about
// to be uploaded turns that into a failure before the upload rather than a discovery
// afterwards.
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

// Cutting a release is one `gh` call. What is worth a task is everything that has
// to be true before it: this is the last point at which a mistake costs nothing,
// because until the draft is published there is no tag, no Marketplace upload and
// nothing to retract. Publishing the draft is left to a person, in the browser,
// where the notes are written — and a release published by a token rather than a
// person would start no workflow at all.
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

        // Pruned, because a stale `refs/remotes/origin/<branch>` left behind by a
        // branch deleted at origin — which is what GitHub does to a merged branch by
        // default — would otherwise vouch for a commit origin no longer references.
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

        // `--prerelease` so GitHub's own label agrees with the channel the version
        // picks: a version carrying an identifier goes to that channel and reaches
        // only the people subscribed to it, which is what a pre-release means.
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
    // returning a status, and a Gradle daemon started before a tool was installed
    // keeps the PATH it was started with — so "not on PATH" here can mean "run
    // ./gradlew --stop and try again" rather than anything being missing.
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
            // Only stdout is the value. git writes advice and warnings to stderr, and
            // a caller parsing a commit SHA out of a merged stream would take them
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
        // and alphabetically among themselves, which happens to be the order the
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
