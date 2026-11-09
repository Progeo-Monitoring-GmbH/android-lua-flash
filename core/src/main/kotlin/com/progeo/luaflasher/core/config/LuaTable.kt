package com.progeo.luaflasher.core.config

/**
 * The Lua subset used by config files: `return { name = value, ... }` with nested tables,
 * lists (`{ 1, 2 }`) and `["key"] = value` fields. No expressions or function calls.
 */
sealed interface LuaValue {
    data class Str(val value: String) : LuaValue
    data class Num(val literal: String) : LuaValue
    data class Bool(val value: Boolean) : LuaValue
    data object Nil : LuaValue
    data class Table(val fields: List<LuaField>) : LuaValue {
        operator fun get(name: String): LuaField? = fields.firstOrNull { it.name == name && !it.commented }
    }
}

/**
 * A field of a table. [commented] fields come from comment lines like `-- timeout_sec = 600`
 * in the template: optional settings that are off unless the profile sets them.
 */
data class LuaField(
    val name: String,
    val value: LuaValue,
    val commented: Boolean = false,
    val description: String? = null,
    /** A list entry (`{ 1, 2 }`); [name] is its 1-based index. */
    val positional: Boolean = false,
) {
    /** A template value such as "<wifi-ssid>" that must never be written to a device. */
    val isPlaceholder: Boolean get() = value is LuaValue.Str && PLACEHOLDER.containsMatchIn(value.value)

    companion object {
        private val PLACEHOLDER = Regex("<[^<>]+>")
    }
}

class LuaParseException(message: String, val line: Int) : Exception("line $line: $message")

/**
 * Parser for config files and `device_config.lua.template`. Comment lines that look like a
 * field become [LuaField.commented] fields; a comment after a field on the same line becomes
 * its description.
 */
class LuaTableParser private constructor(private val src: String) {
    private var pos = 0
    private var line = 1

    private sealed interface Tok {
        val line: Int
        data class Name(val text: String, override val line: Int) : Tok
        data class Sym(val c: Char, override val line: Int) : Tok
        data class Str(val value: String, override val line: Int) : Tok
        data class Num(val literal: String, override val line: Int) : Tok
        data class Comment(val text: String, override val line: Int) : Tok
        data class Eof(override val line: Int) : Tok
    }

    private val tokens = ArrayList<Tok>()
    private var t = 0

    private fun lex() {
        while (true) {
            skipSpace()
            if (pos >= src.length) {
                tokens += Tok.Eof(line)
                return
            }
            val c = src[pos]
            when {
                src.startsWith("--", pos) -> lexComment()
                c == '"' || c == '\'' -> tokens += Tok.Str(lexString(c), line)
                c == '[' && (src.startsWith("[[", pos) || src.startsWith("[=", pos)) -> {
                    val startLine = line
                    tokens += Tok.Str(lexLong() ?: throw LuaParseException("bad long string", line), startLine)
                }
                c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) -> tokens += Tok.Num(lexNumber(), line)
                c == '-' -> {
                    pos++
                    skipSpace()
                    if (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) {
                        tokens += Tok.Num("-" + lexNumber(), line)
                    } else {
                        throw LuaParseException("unexpected '-'", line)
                    }
                }
                c.isLetter() || c == '_' -> {
                    val start = pos
                    while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
                    tokens += Tok.Name(src.substring(start, pos), line)
                }
                c in "{}=,;[]" -> {
                    tokens += Tok.Sym(c, line)
                    pos++
                }
                else -> throw LuaParseException("unexpected '$c'", line)
            }
        }
    }

    private fun skipSpace() {
        while (pos < src.length && src[pos].isWhitespace()) {
            if (src[pos] == '\n') line++
            pos++
        }
    }

    private fun lexComment() {
        val startLine = line
        pos += 2
        if (pos < src.length && src[pos] == '[') {
            val save = pos
            val long = lexLong()
            if (long != null) {
                tokens += Tok.Comment(long, startLine)
                return
            }
            pos = save
        }
        val end = src.indexOf('\n', pos).let { if (it < 0) src.length else it }
        tokens += Tok.Comment(src.substring(pos, end), startLine)
        pos = end
    }

    /** `[[...]]` / `[==[...]==]` at [pos]; null if it is not a long bracket. */
    private fun lexLong(): String? {
        var p = pos + 1
        var level = 0
        while (p < src.length && src[p] == '=') { level++; p++ }
        if (p >= src.length || src[p] != '[') return null
        val close = "]" + "=".repeat(level) + "]"
        val end = src.indexOf(close, p + 1)
        if (end < 0) throw LuaParseException("unfinished long string", line)
        var body = src.substring(p + 1, end)
        if (body.startsWith("\r\n")) body = body.substring(2) else if (body.startsWith("\n")) body = body.substring(1)
        line += src.substring(pos, end).count { it == '\n' }
        pos = end + close.length
        return body
    }

    private fun lexString(quote: Char): String {
        val sb = StringBuilder()
        pos++
        while (true) {
            if (pos >= src.length) throw LuaParseException("unfinished string", line)
            val c = src[pos++]
            when (c) {
                quote -> return sb.toString()
                '\n' -> throw LuaParseException("unfinished string", line)
                '\\' -> {
                    if (pos >= src.length) throw LuaParseException("unfinished string", line)
                    val e = src[pos++]
                    when (e) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'a' -> sb.append('\u0007')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'v' -> sb.append('\u000B')
                        '\\', '"', '\'' -> sb.append(e)
                        '\n' -> { sb.append('\n'); line++ }
                        'x' -> {
                            val hex = src.substring(pos, minOf(pos + 2, src.length))
                            if (hex.length != 2 || hex.any { it.digitToIntOrNull(16) == null }) throw LuaParseException("bad \\x escape", line)
                            sb.append(hex.toInt(16).toChar())
                            pos += 2
                        }
                        'z' -> skipSpace()
                        'u' -> {
                            val close = src.indexOf('}', pos)
                            if (src.getOrNull(pos) != '{' || close < 0) throw LuaParseException("bad \\u escape", line)
                            sb.appendCodePoint(src.substring(pos + 1, close).toInt(16))
                            pos = close + 1
                        }
                        in '0'..'9' -> {
                            var end = pos - 1
                            while (end < src.length && end < pos + 2 && src[end].isDigit()) end++
                            val v = src.substring(pos - 1, end).toInt()
                            if (v > 255) throw LuaParseException("bad decimal escape", line)
                            sb.append(v.toChar())
                            pos = end
                        }
                        else -> throw LuaParseException("bad escape \\$e", line)
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun lexNumber(): String {
        val m = NUMBER.matchAt(src, pos) ?: throw LuaParseException("bad number", line)
        pos += m.value.length
        return m.value
    }

    // --- parser ---

    private fun peek(): Tok = tokens[t]

    private fun next(): Tok = tokens[t++]

    private fun skipComments(): List<Tok.Comment> {
        val out = ArrayList<Tok.Comment>()
        while (peek() is Tok.Comment) out += next() as Tok.Comment
        return out
    }

    private fun expectSym(c: Char) {
        skipComments()
        val tok = next()
        if (tok !is Tok.Sym || tok.c != c) throw LuaParseException("expected '$c'", tok.line)
    }

    private fun parseChunk(): LuaValue.Table {
        skipComments()
        val ret = next()
        if (ret !is Tok.Name || ret.text != "return") throw LuaParseException("expected 'return {'", ret.line)
        skipComments()
        val table = parseTable()
        skipComments()
        val end = next()
        if (end is Tok.Sym && end.c == ';') skipComments().also { if (peek() !is Tok.Eof) throw LuaParseException("text after the table", peek().line) }
        else if (end !is Tok.Eof) throw LuaParseException("text after the table", end.line)
        return table
    }

    private fun parseTable(): LuaValue.Table {
        expectSym('{')
        val fields = ArrayList<LuaField>()
        var lastField = -1
        var lastFieldLine = -1

        // A comment on the line of the previous field describes it; a comment line that looks
        // like a field is an optional field; other comments are ignored.
        fun absorbComments() {
            while (peek() is Tok.Comment) {
                val comment = next() as Tok.Comment
                if (lastField >= 0 && comment.line == lastFieldLine && fields[lastField].description == null) {
                    fields[lastField] = fields[lastField].copy(description = comment.text.trim())
                } else {
                    commentedField(comment)?.let { fields += it }
                }
            }
        }

        var position = 0
        while (true) {
            absorbComments()
            val tok = peek()
            if (tok is Tok.Sym && tok.c == '}') {
                next()
                return LuaValue.Table(fields)
            }
            val field = when {
                // name = value
                tok is Tok.Name && tokens.getOrNull(t + 1).let { it is Tok.Sym && it.c == '=' } -> {
                    next()
                    expectSym('=')
                    skipComments()
                    LuaField(tok.text, parseValue())
                }
                // ["name"] = value / [3] = value
                tok is Tok.Sym && tok.c == '[' -> {
                    next()
                    skipComments()
                    val key = when (val k = parseValue()) {
                        is LuaValue.Str -> k.value
                        is LuaValue.Num -> k.literal
                        else -> throw LuaParseException("unsupported key", tok.line)
                    }
                    expectSym(']')
                    expectSym('=')
                    skipComments()
                    LuaField(key, parseValue())
                }
                // positional (list) value
                else -> {
                    position++
                    LuaField(position.toString(), parseValue(), positional = true)
                }
            }
            fields += field
            lastField = fields.size - 1
            lastFieldLine = tokens[t - 1].line
            absorbComments()
            val sep = peek()
            if (sep is Tok.Sym && (sep.c == ',' || sep.c == ';')) {
                next()
            } else if (!(sep is Tok.Sym && sep.c == '}')) {
                throw LuaParseException("expected ',' or '}'", sep.line)
            }
        }
    }

    private fun parseValue(): LuaValue = when (val tok = peek()) {
        is Tok.Str -> { next(); LuaValue.Str(tok.value) }
        is Tok.Num -> { next(); LuaValue.Num(tok.literal) }
        is Tok.Sym -> if (tok.c == '{') parseTable() else throw LuaParseException("unexpected '${tok.c}'", tok.line)
        is Tok.Name -> {
            next()
            when (tok.text) {
                "true" -> LuaValue.Bool(true)
                "false" -> LuaValue.Bool(false)
                "nil" -> LuaValue.Nil
                else -> throw LuaParseException("unsupported value '${tok.text}'", tok.line)
            }
        }
        else -> throw LuaParseException("expected a value", tok.line)
    }

    companion object {
        private val NUMBER = Regex("0[xX][0-9a-fA-F]+|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")
        private val COMMENTED_FIELD = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$")

        fun parse(source: String): LuaValue.Table {
            val parser = LuaTableParser(source.removePrefix("﻿"))
            parser.lex()
            return parser.parseChunk()
        }

        /** `-- name = value, -- description` as an optional field, or null if the comment is prose. */
        private fun commentedField(comment: Tok.Comment): LuaField? {
            val m = COMMENTED_FIELD.find(comment.text) ?: return null
            val name = m.groupValues[1]
            val rest = m.groupValues[2]
            return try {
                val inner = LuaTableParser("return { $name = $rest\n}")
                inner.lex()
                val table = inner.parseChunk()
                val field = table.fields.singleOrNull { !it.commented } ?: return null
                if (field.value is LuaValue.Table) return null
                field.copy(commented = true)
            } catch (e: LuaParseException) {
                null
            }
        }
    }
}
