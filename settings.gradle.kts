@file:Suppress("UnstableApiUsage")

import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Applied here rather than in build.gradle.kts so that the IntelliJ Platform
// repositories and the plugin's own version are available to every project,
// `frontend` included — a plugin module cannot declare them for itself.
plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

rootProject.name = "pinguard"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

// A content module depending on `intellij.platform.frontend`, which is what gets
// PinGuard loaded where the editor tabs are — in a remote development or Code With
// Me session, the frontend process rather than the backend the plugin is installed
// on. See src/main/resources/META-INF/plugin.xml.
include("frontend")
