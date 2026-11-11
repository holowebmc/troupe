pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

// One subproject per Minecraft version, like Vitrine; only 26.3 for now.
stonecutter {
    create(rootProject) {
        versions("26.3")
        vcsVersion = "26.3"
    }
}

rootProject.name = "troupe"
