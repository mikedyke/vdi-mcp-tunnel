plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"   // 2.x — required for 2024.2+ / 2026.x SDKs
}

group = "com.vditunnel"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Match the VDI IDE. Community is enough for a plain tool window.
        // Pin to your exact build from Help > About (e.g. "2026.2.1") in gradle.properties.
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
    }
    implementation("com.google.zxing:core:3.5.3")   // bundled into the plugin distribution
}

kotlin {
    jvmToolchain(21)   // IntelliJ 2024.2+/2026.x build against JDK 21
}
