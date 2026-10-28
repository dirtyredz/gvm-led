package com.dirtyredz.gvmled.data.json

/**
 * A small strict JSON reader/writer for the app's own bounded documents (bundled release contract,
 * GitHub release metadata, the phone manifest and the saved light catalog). Android's `org.json` is
 * unavailable in local unit tests, and these callers need strictness org.json does not give:
 * duplicate keys, trailing data and over-deep nesting are rejected, and numbers keep their text so
 * integers are read exactly.
 */
sealed interface JsonValue

data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = fields[key]
    val keys: Set<String> get() = fields.keys
    fun string(key: String): String? = (fields[key] as? JsonString)?.value
    fun long(key: String): Long? = (fields[key] as? JsonNumber)?.longOrNull()
    fun bool(key: String): Boolean? = (fields[key] as? JsonBool)?.value
    fun obj(key: String): JsonObject? = fields[key] as? JsonObject
    fun array(key: String): JsonArray? = fields[key] as? JsonArray
}

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonString(val value: String) : JsonValue

/** [text] is the literal as written; only plain integers convert with [longOrNull]. */
data class JsonNumber(val text: String) : JsonValue {
    fun longOrNull(): Long? = if (INTEGER.matches(text)) text.toLongOrNull() else null

    private companion object {
        val INTEGER = Regex("-?(0|[1-9][0-9]*)")
    }
}

data class JsonBool(val value: Boolean) : JsonValue

data object JsonNull : JsonValue

class JsonException(message: String) : Exception(message)

object Json {
    private const val MAX_DEPTH = 32

    /** Parses one complete document. Throws [JsonException] on any syntax or strictness violation. */
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.value(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Trailing data at ${parser.position}")
        return value
    }

    fun parseObject(text: String): JsonObject =
        parse(text) as? JsonObject ?: throw JsonException("Expected a JSON object")

    fun write(value: JsonValue): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonObject -> {
                out.append('{')
                value.fields.entries.forEachIndexed { index, (key, field) ->
                    if (index > 0) out.append(',')
                    quote(out, key)
                    out.append(':')
                    append(out, field)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            is JsonString -> quote(out, value.value)
            is JsonNumber -> out.append(value.text)
            is JsonBool -> out.append(value.value)
            JsonNull -> out.append("null")
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (char in text) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' -> out.append("\\u%04x".format(char.code))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        var position = 0
            private set

        fun atEnd() = position >= text.length

        fun skipWhitespace() {
            while (!atEnd() && text[position] in " \t\r\n") position++
        }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonException("Nesting too deep")
            skipWhitespace()
            if (atEnd()) throw JsonException("Unexpected end of input")
            return when (val char = text[position]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> JsonString(string())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (char == '-' || char in '0'..'9') number() else throw JsonException("Unexpected '$char' at $position")
            }
        }

        private fun objectValue(depth: Int): JsonObject {
            position++
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { position++; return JsonObject(fields) }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonException("Expected a key at $position")
                val key = string()
                skipWhitespace()
                expect(':')
                if (fields.put(key, value(depth + 1)) != null) throw JsonException("Duplicate key \"$key\"")
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return JsonObject(fields)
                    else -> throw JsonException("Expected ',' or '}' at ${position - 1}")
                }
            }
        }

        private fun arrayValue(depth: Int): JsonArray {
            position++
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { position++; return JsonArray(items) }
            while (true) {
                items += value(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return JsonArray(items)
                    else -> throw JsonException("Expected ',' or ']' at ${position - 1}")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val char = next()
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> out.append(escape())
                    char < ' ' -> throw JsonException("Control character in string at ${position - 1}")
                    else -> out.append(char)
                }
            }
        }

        private fun escape(): Char = when (val char = next()) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (position + 4 > text.length) throw JsonException("Truncated escape")
                val code = text.substring(position, position + 4).toIntOrNull(16) ?: throw JsonException("Bad escape")
                position += 4
                code.toChar()
            }
            else -> throw JsonException("Bad escape '\\$char'")
        }

        private fun number(): JsonNumber {
            val match = NUMBER.matchAt(text, position) ?: throw JsonException("Bad number at $position")
            position += match.value.length
            return JsonNumber(match.value)
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, position)) throw JsonException("Unexpected token at $position")
            position += word.length
            return value
        }

        private fun peek(): Char? = text.getOrNull(position)

        private fun next(): Char = text.getOrNull(position++) ?: throw JsonException("Unexpected end of input")

        private fun expect(char: Char) {
            if (next() != char) throw JsonException("Expected '$char' at ${position - 1}")
        }

        private companion object {
            val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        }
    }
}
