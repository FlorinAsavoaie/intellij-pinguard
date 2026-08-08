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

// Everything PinGuard does happens where the editor tabs are, which in a remote
// development or Code With Me session is the frontend process rather than the
// backend the plugin is installed on. Keeping the code in a content module that
// depends on `intellij.platform.frontend` is what gets it loaded there; see
// src/main/resources/META-INF/plugin.xml.
include("frontend")
