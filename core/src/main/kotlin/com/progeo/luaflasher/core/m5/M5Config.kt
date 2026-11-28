package com.progeo.luaflasher.core.m5

import com.progeo.luaflasher.core.FlasherException

class M5ConfigException(userMessage: String, detail: String? = null) : FlasherException(userMessage, detail)

/** A value of the M5's `config.py`: the literals the MicroPython firmware reads. */
sealed interface M5Value {
    data class Str(val value: String) : M5Value
    data class Int(val value: Long) : M5Value
    data class Bool(val value: Boolean) : M5Value
    data object None : M5Value

    /** As shown to people (no quotes). */
    val display: String
        get() = when (this) {
            is Str -> value
            is Int -> value.toString()
            is Bool -> if (value) "True" else "False"
            None -> "None"
        }

    /** As a Python literal in config.py. */
    val python: String
        get() = when (this) {
            is Str -> pyString(value)
            is Int -> value.toString()
            is Bool -> if (value) "True" else "False"
            None -> "None"
        }

    val truthy: Boolean
        get() = when (this) {
            is Str -> value.isNotEmpty()
            is Int -> value != 0L
            is Bool -> value
            None -> false
        }
}

/**
 * The `config.py` of the M5Stack MicroPython firmware (m5-stack, `mPy/device/config_keys.py`),
 * parsed into its values in file order. Reads three sources:
 *
 * - the config QR code of the backend (device provisioning): a compact config.py whose first line
 *   is [MARKER] (`detection-docker-setup`, `render_m5_qr_payload`);
 * - the full config.py the backend renders or a person wrote from `config_example.py`;
 * - the config.py read back from a device.
 *
 * Only the Python the firmware's config uses is understood: `NAME = <expr>` where `<expr>` is a
 * string, integer, `True`/`False`/`None`, an earlier name, or several of these joined with `+`
 * (`PLATFORM_DEVICE_HASH + "-RS"`). Comments and blank lines are skipped. Anything else is refused,
 * so a config is never half understood.
 */
class M5Config private constructor(val values: Map<String, M5Value>) {

    operator fun get(name: String): M5Value? = values[name]

    fun string(name: String): String? = (values[name] as? M5Value.Str)?.value

    val deviceHash: String? get() = string("PLATFORM_DEVICE_HASH")?.takeIf { it.isNotBlank() }
    val projectId: String? get() = string("PLATFORM_PROJECT_ID")
    val host: String? get() = string("PLATFORM_HOST")
    val model: String? get() = string("PLATFORM_MODEL")
    val rs485Enabled: Boolean get() = values["PLATFORM_RS485_ENABLED"]?.truthy == true

    /** A copy with [name] set to [value] (appended if new). */
    fun with(name: String, value: M5Value): M5Config {
        require(NAME.matches(name)) { "not a config name: $name" }
        return M5Config(LinkedHashMap(values).apply { put(name, value) })
    }

    /** Problems that would stop the firmware at start, or make it misbehave; empty when fine. */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        val need = REQUIRED + if (rs485Enabled) REQUIRED_RS485 else emptyList()
        val missing = need.filter { it !in values }
        if (missing.isNotEmpty()) out += "missing ${missing.joinToString()}"
        for (name in STRING_KEYS) values[name]?.let { if (it !is M5Value.Str) out += "$name must be text" }
        for (name in INT_KEYS) values[name]?.let { if (it !is M5Value.Int) out += "$name must be a whole number" }
        projectId?.let { if (!it.all(Char::isDigit) || it.isEmpty()) out += "PLATFORM_PROJECT_ID must be digits" }
        string("PLATFORM_PATH")?.let { if (!it.endsWith("sample/")) out += "PLATFORM_PATH must end with sample/" }
        (values["PLATFORM_PULLDOWN"] as? M5Value.Int)?.let { if (it.value !in PULLDOWNS) out += "PLATFORM_PULLDOWN must be one of ${PULLDOWNS.joinToString()}" }
        if (values["PLATFORM_TOKEN"]?.let { it is M5Value.Str && it.value.isNotBlank() } == false) out += "PLATFORM_TOKEN is empty"
        string("ALARM_NUMBER")?.let { if (it.isNotEmpty() && !ALARM_NUMBER.matches(it)) out += "ALARM_NUMBER must be international (+49… or 0049…)" }
        return out
    }

    /**
     * config.py as written to the device: [MARKER] first (so the app recognises the file when it
     * reads it back), then every value as a literal, in order. Equivalent to the source it was
     * parsed from: names are resolved, so `PLATFORM_RS485_TOKEN = PLATFORM_TOKEN` becomes the token.
     */
    fun render(): String = buildString {
        append(MARKER).append('\n')
        append("# Geraete-Konfiguration der M5-MicroPython-Firmware, geschrieben von der ProGeo-Flasher-App.\n")
        append("# Enthaelt den Geraete-Token.\n")
        for ((name, value) in values) append(name).append(" = ").append(value.python).append('\n')
    }

    override fun equals(other: Any?) = other is M5Config && other.values == values
    override fun hashCode() = values.hashCode()
    override fun toString() = "M5Config(${values.keys.joinToString()})"

    companion object {
        /** First line of the backend's M5 config QR code and of config.py files this app writes. */
        const val MARKER = "# progeo:m5-config:1"

        /** Where the firmware loads it from (m5-stack `mPy/tools/upload.py`, APP_DIR). */
        const val DEVICE_PATH = "/flash/progeo/config.py"

        val REQUIRED = listOf(
            "PLATFORM_PROJECT_ID", "PLATFORM_DEVICE_HASH", "PLATFORM_HOST", "PLATFORM_PATH",
            "PLATFORM_TOKEN", "PLATFORM_MODEL", "PLATFORM_DEVICE_VER", "PLATFORM_PULLDOWN",
            "PLATFORM_INTERVAL_SEC", "PLATFORM_POLL_SEC", "PLATFORM_SENSORS", "PLATFORM_PE",
        )
        val REQUIRED_RS485 = listOf(
            "PLATFORM_RS485_HOST", "PLATFORM_RS485_PATH", "PLATFORM_RS485_HASH",
            "PLATFORM_RS485_TOKEN", "PLATFORM_RS485_INTERVAL_SEC",
        )

        /** Device tokens: masked on screen. */
        val SECRET_KEYS = setOf("PLATFORM_TOKEN", "PLATFORM_RS485_TOKEN")

        private val STRING_KEYS = listOf(
            "PLATFORM_PROJECT_ID", "PLATFORM_DEVICE_HASH", "PLATFORM_HOST", "PLATFORM_PATH", "PLATFORM_TOKEN",
            "PLATFORM_MODEL", "PLATFORM_DEVICE_VER", "ALARM_NUMBER", "APN",
            "PLATFORM_RS485_HOST", "PLATFORM_RS485_PATH", "PLATFORM_RS485_HASH", "PLATFORM_RS485_TOKEN",
        )
        private val INT_KEYS = listOf(
            "PLATFORM_PULLDOWN", "PLATFORM_INTERVAL_SEC", "PLATFORM_POLL_SEC", "PLATFORM_SENSORS",
            "PLATFORM_PE", "PLATFORM_RS485_INTERVAL_SEC", "WATCHDOG_SEC",
        )

        /** 136 = 100k, 72 = 10k, 40 = 1k, 24 = 100 Ohm (firmware logic.py). */
        val PULLDOWNS = listOf(136L, 72L, 40L, 24L)

        private val NAME = Regex("[A-Z_][A-Z0-9_]*")
        private val ASSIGNMENT = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)$""")
        private val ALARM_NUMBER = Regex("""^(\+|00)[1-9][0-9]{6,14}$""")

        /**
         * Whether [text] is meant as an M5 config (rather than a Lua/JSON config or a label):
         * it starts with [MARKER], or it assigns `PLATFORM_DEVICE_HASH` like a config.py.
         */
        fun looksLike(text: String): Boolean {
            val t = text.trimStart('﻿', ' ', '\t', '\r', '\n')
            if (t.startsWith(MARKER)) return true
            if (t.startsWith("{") || t.startsWith("return")) return false
            return Regex("""(?m)^\s*PLATFORM_DEVICE_HASH\s*=""").containsMatchIn(t)
        }

        /** Parses config.py text; throws [M5ConfigException] on anything it does not understand. */
        fun parse(text: String): M5Config {
            val values = LinkedHashMap<String, M5Value>()
            text.removePrefix("﻿").lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val m = ASSIGNMENT.matchEntire(line)
                    ?: throw M5ConfigException("Line ${index + 1} of the M5 config is not understood.", "line ${index + 1}: $raw")
                val (name, expr) = m.destructured
                values[name] = try {
                    Expr(expr, values).parse()
                } catch (e: IllegalArgumentException) {
                    throw M5ConfigException("Line ${index + 1} of the M5 config is not understood: ${e.message}", "line ${index + 1}: $raw")
                }
            }
            if (values.isEmpty()) throw M5ConfigException("The M5 config is empty.")
            return M5Config(values)
        }

        /** Parses and checks; throws if the firmware would refuse the config. */
        fun parseValid(text: String): M5Config {
            val config = parse(text)
            val problems = config.problems()
            if (problems.isNotEmpty()) throw M5ConfigException("The M5 config is incomplete: ${problems.joinToString("; ")}")
            return config
        }
    }
}

/** Python string literal with JSON-style escapes (Python reads them the same way). */
internal fun pyString(s: String): String = buildString {
    append('"')
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 || c.code > 0x7E -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

/** One right-hand side: terms joined by `+`, then an optional `# comment`. */
private class Expr(private val s: String, private val known: Map<String, M5Value>) {
    private var i = 0

    fun parse(): M5Value {
        val terms = ArrayList<M5Value>()
        while (true) {
            skipSpace()
            terms += term()
            skipSpace()
            if (i < s.length && s[i] == '+') {
                i++
                continue
            }
            break
        }
        skipSpace()
        require(i == s.length || s[i] == '#') { "unexpected '${s.substring(i)}'" }
        if (terms.size == 1) return terms[0]
        return when {
            terms.all { it is M5Value.Str } -> M5Value.Str(terms.joinToString("") { (it as M5Value.Str).value })
            terms.all { it is M5Value.Int } -> M5Value.Int(terms.sumOf { (it as M5Value.Int).value })
            else -> throw IllegalArgumentException("cannot add text and numbers")
        }
    }

    private fun skipSpace() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun term(): M5Value {
        require(i < s.length) { "value missing" }
        val c = s[i]
        return when {
            c == '"' || c == '\'' -> string(c)
            c == '-' || c.isDigit() -> number()
            c.isLetter() || c == '_' -> name()
            else -> throw IllegalArgumentException("unexpected '$c'")
        }
    }

    private fun number(): M5Value {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '_')) i++
        val text = s.substring(start, i).replace("_", "")
        require(text.isNotEmpty() && text != "-" && (i == s.length || !s[i].isLetterOrDigit() && s[i] != '.')) { "not a whole number" }
        return M5Value.Int(text.toLong())
    }

    private fun name(): M5Value {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        return when (val n = s.substring(start, i)) {
            "True" -> M5Value.Bool(true)
            "False" -> M5Value.Bool(false)
            "None" -> M5Value.None
            else -> known[n] ?: throw IllegalArgumentException("$n is not defined above")
        }
    }

    private fun string(quote: Char): M5Value {
        i++
        val out = StringBuilder()
        while (true) {
            require(i < s.length) { "unterminated text" }
            val c = s[i++]
            if (c == quote) break
            if (c != '\\') {
                out.append(c)
                continue
            }
            require(i < s.length) { "unterminated text" }
            when (val e = s[i++]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                '\\', '"', '\'', '/' -> out.append(e)
                'u' -> {
                    require(i + 4 <= s.length) { "bad \\u escape" }
                    out.append(s.substring(i, i + 4).toInt(16).toChar())
                    i += 4
                }
                'x' -> {
                    require(i + 2 <= s.length) { "bad \\x escape" }
                    out.append(s.substring(i, i + 2).toInt(16).toChar())
                    i += 2
                }
                else -> throw IllegalArgumentException("unknown escape \\$e")
            }
        }
        return M5Value.Str(out.toString())
    }
}
