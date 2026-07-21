// Bridge-side half of the cross-language codec parity test.
//
// It compiles the two mirrored files OUT OF the plugin module (no copies -- the test
// must exercise the code that actually ships) but nothing else, so this build needs
// neither the IntelliJ Platform plugin nor the ~1 GB IDE SDK. `gradle test` here runs
// in seconds on any machine with a JDK.
//
// Corollary worth keeping: if Protocol.kt or Fountain.kt ever grows an IntelliJ import,
// this build stops compiling. That is the intended alarm -- the mirror layer is meant
// to stay plain Kotlin so it can be diffed line-for-line against the Python side.

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
}

repositories { mavenCentral() }

sourceSets {
    main {
        kotlin.setSrcDirs(listOf("../bridge-plugin/src/main/kotlin"))
        kotlin.include("com/vditunnel/bridge/Protocol.kt", "com/vditunnel/bridge/Fountain.kt")
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Deliberately no jvmToolchain pin: unlike the plugin (which must build against the IDE's
// JDK 21) this build only compiles plain Kotlin, so it should run on whatever JDK the
// developer already has rather than provisioning another one.

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
