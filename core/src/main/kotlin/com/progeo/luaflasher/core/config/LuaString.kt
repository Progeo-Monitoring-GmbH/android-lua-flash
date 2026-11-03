package com.progeo.luaflasher.core.config

/** Lua string literals that survive any content (passwords and tokens may contain anything). */
object LuaString {
    /**
     * Double-quoted Lua literal: `\\`, `\"`, `\n` escaped, other control characters as
     * three-digit `\ddd` (always three digits, so a following digit is safe). Other characters
     * stay as they are; the generated file is written as UTF-8, and Lua strings are 8-bit clean,
     * so the bytes on the device are the UTF-8 bytes of [value].
     */
    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c.code < 0x20 || c.code == 0x7F -> sb.append('\\').append("%03d".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
