import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.progeo.luaflasher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.progeo.luaflasher"
        minSdk = 26
        targetSdk = 36
        // Compared with min_app_version of the channel file.
        versionCode = 1
        versionName = "0.1.0"

        // ProGeo backend for login and controller configs (API.md: not hard-coded in the code).
        buildConfigField("String", "BACKEND_URL", "\"https://api.progeo.com/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

/**
 * Copies keys/firmware-signing.pub.json into the app (PLAN.md section 3) and
 * fails the build if it is missing or malformed. Debug builds may add test keys
 * with -PextraFirmwareKeys=<file>; release builds never do.
 */
abstract class FirmwareKeysTask : DefaultTask() {
    @get:InputFile
    abstract val keysFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val extraKeysFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val keys = readKeys(keysFile.get().asFile) + (extraKeysFile.orNull?.asFile?.let(::readKeys) ?: emptyList())
        val ids = keys.map { it["id"] }
        require(ids.size == ids.toSet().size) { "Duplicate key IDs in firmware keys: $ids" }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        out.resolve("firmware-signing.pub.json")
            .writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("keys" to keys))))
    }

    private fun readKeys(file: File): List<Map<String, String>> {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(file) as? Map<String, Any?> ?: error("$file: not a JSON object")
        @Suppress("UNCHECKED_CAST")
        val keys = root["keys"] as? List<Map<String, Any?>> ?: error("$file: no \"keys\" array")
        require(keys.isNotEmpty()) { "$file: no keys" }
        return keys.map { key ->
            val id = key["id"] as? String ?: error("$file: key without id")
            val b64 = key["ed25519"] as? String ?: error("$file: key $id without ed25519")
            require(id.isNotBlank() && ':' !in id) { "$file: invalid key id '$id'" }
            require(Base64.getDecoder().decode(b64).size == 32) { "$file: key $id is not a raw 32-byte Ed25519 key" }
            mapOf("id" to id, "ed25519" to b64)
        }
    }
}

androidComponents {
    onVariants { variant ->
        val taskName = "copy${variant.name.replaceFirstChar { it.uppercase() }}FirmwareKeys"
        val keysTask = tasks.register<FirmwareKeysTask>(taskName) {
            keysFile.set(
                rootProject.layout.projectDirectory.file(
                    providers.gradleProperty("firmwareKeys").orElse("keys/firmware-signing.pub.json")
                )
            )
            if (variant.buildType == "debug") {
                providers.gradleProperty("extraFirmwareKeys").orNull?.let { extraKeysFile.set(file(it)) }
            }
        }
        variant.sources.assets?.addGeneratedSourceDirectory(keysTask, FirmwareKeysTask::outputDir)
    }
}

// :core compiles against the JVM Tink; on Android the tink-android flavour of the same API is used.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.google.crypto.tink:tink"))
            .using(module("com.google.crypto.tink:tink-android:${libs.versions.tink.get()}"))
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":progeo-core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.usb.serial)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.tink)
}
