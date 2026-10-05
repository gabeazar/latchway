// Pure JVM: the key schedule, proofs, envelopes and chunk framing, tested
// against ../../testdata/vectors.json like every other implementation.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
    // The fixtures live at the repository root.
    systemProperty("latchway.vectors", rootDir.resolve("../testdata/vectors.json").absolutePath)
}
