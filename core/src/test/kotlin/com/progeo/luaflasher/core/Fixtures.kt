package com.progeo.luaflasher.core

import java.io.File

object Fixtures {
    fun bytes(name: String): ByteArray =
        Fixtures::class.java.getResourceAsStream("/fixtures/$name")?.use { it.readBytes() }
            ?: error("fixture $name missing")

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)

    /** The real keys/firmware-signing.pub.json (path passed by Gradle). */
    fun publicKeysJson(): String = File(System.getProperty("firmwareKeys") ?: "../keys/firmware-signing.pub.json").readText()
}
