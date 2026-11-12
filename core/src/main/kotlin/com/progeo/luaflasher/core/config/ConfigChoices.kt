package com.progeo.luaflasher.core.config

data class Choice(val value: Long, val label: String)

/**
 * Settings of `device_config.lua` that only take a fixed set of values. The editor offers
 * them as a selection instead of a free number; the lists match the web portal
 * (frontend deviceParams.ts) and the backend (ProgeoDevice.Resistance).
 */
object ConfigChoices {
    /** measurement.interval_sec */
    val INTERVAL = listOf(
        Choice(300, "5min"),
        Choice(600, "10min"),
        Choice(900, "15min"),
        Choice(1800, "30min"),
        Choice(3600, "1h"),
        Choice(7200, "2h"),
        Choice(14400, "4h"),
        Choice(28800, "8h"),
        Choice(43200, "12h"),
        Choice(86400, "24h"),
    )

    /** measurement.pulldown_resistance (ProgeoDevice.Resistance) */
    val RESISTANCE = listOf(
        Choice(136, "100k (default)"),
        Choice(72, "10k"),
        Choice(40, "1k"),
        Choice(24, "100"),
        Choice(8, "Off"),
    )

    /** control.poll_sec - 0: the controller never polls for commands. */
    val POLL = listOf(
        Choice(60, "1min"),
        Choice(300, "5min"),
        Choice(900, "15min"),
        Choice(3600, "1h"),
        Choice(14400, "4h"),
        Choice(43200, "12h"),
        Choice(86400, "24h"),
        Choice(0, "Never"),
    )

    private val byPath = mapOf(
        "measurement.interval_sec" to INTERVAL,
        "measurement.pulldown_resistance" to RESISTANCE,
        "control.poll_sec" to POLL,
    )

    val paths: Set<String> get() = byPath.keys

    /** The allowed values of the setting at [path], or null if it takes any value. */
    fun of(path: String): List<Choice>? = byPath[path]

    /** The choice [text] (a config value, e.g. "1800" or "1800.0") stands for; null if none. */
    fun find(path: String, text: String?): Choice? {
        val number = text?.trim()?.toDoubleOrNull() ?: return null
        return of(path)?.firstOrNull { it.value.toDouble() == number }
    }
}
