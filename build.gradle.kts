import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

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
        // No `version` here: it defaults to the project's, which is set from the
        // same gradle property at the top of this file.

        // The descriptor's change notes come from CHANGELOG.md so the two cannot
        // disagree. Released versions render their own section; before a release
        // there is only [Unreleased] to show.
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
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
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
        }
    }
}
