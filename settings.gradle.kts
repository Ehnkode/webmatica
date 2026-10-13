pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("dev.kikugie.stonecutter") version providers.gradleProperty("stonecutter_version").get()
        id("dev.kikugie.loom-back-compat") version providers.gradleProperty("loom_back_compat_version").get()
        id("org.gradle.toolchains.foojay-resolver-convention") version providers.gradleProperty("foojay_version").get()
    }
}

plugins {
    id("dev.kikugie.stonecutter")
    id("dev.kikugie.loom-back-compat")
    id("org.gradle.toolchains.foojay-resolver-convention")
}

// One jar per Minecraft version from the same sources. The code in src/ is written for vcsVersion;
// differences for other versions sit in //? comments (Stonecutter).
stonecutter {
    create(rootProject) {
        versions("1.21.11", "26.1.2", "26.2", "26.3")
        vcsVersion = "1.21.11"
    }
}

rootProject.name = "webmatica"
