import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.tink)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // The real public keys, so tests can check the published channel signature.
    systemProperty("firmwareKeys", rootProject.file("keys/firmware-signing.pub.json").absolutePath)
    // Optional: a downloaded bundle zip (+ .sig next to it) for an end-to-end check.
    providers.gradleProperty("bundleZip").orNull?.let { systemProperty("bundleZip", it) }
    providers.gradleProperty("luaInterpreter").orNull?.let { systemProperty("luaInterpreter", it) }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
