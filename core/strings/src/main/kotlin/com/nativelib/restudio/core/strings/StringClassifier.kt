package com.nativelib.restudio.core.strings

import com.nativelib.restudio.core.common.JsonWriter
import com.nativelib.restudio.core.common.toHexAddr

/**
 * Heuristic tagging of strings that are often relevant during analysis. Tags are hints, not facts:
 * a string tagged URL is only text that looks like a URL. Nothing is resolved or opened.
 */
object StringClassifier {
    private val ERROR_WORDS = listOf("error", "fail", "exception", "invalid", "cannot", "can't", "unable", "denied", "abort", "fatal", "crash", "corrupt", "not found", "unsupported", "illegal")
    private val DEBUG_WORDS = listOf("debug", "verbose", "trace", "log", "assert", "warn", "info")
    private val url = Regex("""(?i)\b(?:https?|wss?|ftp|file|content)://[^\s"'<>]{2,}""")
    private val ipv4 = Regex("""(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?::\d{1,5})?(?![\d.])""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val path = Regex("""(?:^|[\s"'=])(?:/(?:system|data|proc|sys|dev|vendor|sdcard|storage|apex|mnt|etc|tmp|odm|product)\b[^\s"']*|(?:[\w.-]+/)+[\w.-]+\.(?:so|dex|apk|jar|odex|oat|art|xml|json|db|txt|dat|bin|cfg|conf|ini|png|jpg)\b)""")
    private val libName = Regex("""^lib[\w.+-]+\.so(?:\.\d+)*$""")
    private val jniClass = Regex("""\bL?(?:[a-z][a-z0-9_]*/){2,}[A-Za-z_$][\w$]*;?""")
    private val dottedClass = Regex("""^(?:[a-z][a-z0-9_]*\.){2,}[A-Z][\w$]*$""")
    private val function = Regex("""^(?:Java_[\w]+|_Z[\w]+|JNI_On(?:Load|Unload)|[A-Za-z_]\w*::\w+.*|[A-Za-z_]\w{2,}\(.*\))$""")
    private val format = Regex("""%[-+ #0]*\d*(?:\.\d+)?(?:hh|h|ll|l|z|j|t|L)?[diouxXeEfgGcspn%]""")
    private val error = Regex("""(?i)\b(?:error|fail(?:ed|ure)?|exception|invalid|cannot|can't|unable|denied|abort|fatal|crash|corrupt|not found|unsupported|illegal)\b""")
    private val debug = Regex("""(?i)\b(?:debug|verbose|trace|log(?:cat)?|assert|warn(?:ing)?|info)\b|^\[[\w .-]+]|^[A-Z]/\w+""")
    private val config = Regex("""^[\w.-]{2,}\s*[=:]\s*\S+|^\s*[{\[].*[:,].*[}\]]\s*$|^(?:ro|persist|sys|debug)\.[\w.]+$""")

    fun classify(s: String): Set<StringCategory> {
        if (s.length < 3) return emptySet()
        // Cheap character checks gate every regex: classification runs on hundreds of thousands of strings.
        val out = HashSet<StringCategory>(4)
        val hasSlash = s.indexOf('/') >= 0
        val hasDot = s.indexOf('.') >= 0
        if (hasSlash && s.contains("://") && url.containsMatchIn(s)) out += StringCategory.URL
        if (hasDot && s.any { it.isDigit() } && ipv4.containsMatchIn(s)) out += StringCategory.IP_ADDRESS
        if (s.indexOf('@') > 0 && email.containsMatchIn(s)) out += StringCategory.EMAIL
        if (StringCategory.URL !in out && ((hasSlash && path.containsMatchIn(s)) || (s.startsWith("lib") && libName.matches(s)))) out += StringCategory.FILE_PATH
        if (hasSlash && StringCategory.URL !in out && StringCategory.FILE_PATH !in out && jniClass.containsMatchIn(s)) out += StringCategory.CLASS_NAME
        if (hasDot && dottedClass.matches(s)) out += StringCategory.CLASS_NAME
        val t = s.trim()
        if ((t.startsWith("Java_") || t.startsWith("_Z") || t.startsWith("JNI_On") || t.contains("::") || t.contains('(')) && function.matches(t)) out += StringCategory.FUNCTION_NAME
        if (s.indexOf('%') >= 0 && format.containsMatchIn(s)) out += StringCategory.FORMAT_STRING
        val lower = s.lowercase()
        if (ERROR_WORDS.any { lower.contains(it) } && error.containsMatchIn(s)) out += StringCategory.ERROR_MESSAGE
        if ((s.startsWith("[") || (s.length > 2 && s[1] == '/') || DEBUG_WORDS.any { lower.contains(it) }) && debug.containsMatchIn(s)) out += StringCategory.DEBUG_MESSAGE
        if (StringCategory.URL !in out && (s.indexOf('=') > 0 || s.indexOf(':') > 0 || s.indexOf('{') >= 0 || s.indexOf('[') >= 0 || hasDot) && config.containsMatchIn(s)) out += StringCategory.CONFIG_VALUE
        return out
    }
}

/** Deterministic TXT / CSV / JSON renderings of extracted strings. */
object StringReport {
    fun toText(title: String, strings: List<ExtractedString>, truncated: Boolean): String = buildString {
        appendLine("STRINGS — $title")
        appendLine("${strings.size} strings${if (truncated) " (result limit reached; scan stopped early)" else ""}")
        appendLine("Columns: file offset, virtual address, section, encoding, categories, text")
        appendLine()
        strings.forEach { s ->
            append(s.offset.toHexAddr(8)).append('\t').append(s.vaddr?.toHexAddr() ?: "-").append('\t')
                .append(s.section ?: "-").append('\t').append(s.encoding.label).append('\t')
                .append(s.categories.sortedBy { it.ordinal }.joinToString("|") { it.label }.ifEmpty { "-" }).append('\t')
                .appendLine(escape(s.text))
        }
    }

    fun toCsv(strings: List<ExtractedString>): String = buildString {
        appendLine("offset,vaddr,section,encoding,byte_length,categories,text")
        strings.forEach { s ->
            appendLine(listOf(s.offset.toHexAddr(), s.vaddr?.toHexAddr() ?: "", s.section ?: "", s.encoding.label, s.byteLength.toString(),
                s.categories.sortedBy { it.ordinal }.joinToString("|") { it.name }, s.text).joinToString(",") { csv(it) })
        }
    }

    fun toJson(title: String, strings: List<ExtractedString>, truncated: Boolean): String {
        val j = JsonWriter()
        j.beginObject().field("file", title).field("count", strings.size).field("truncated", truncated)
        j.arrayField("strings")
        strings.forEach { s ->
            j.beginObject().field("offset", s.offset).field("vaddr", s.vaddr?.toHexAddr()).field("section", s.section)
                .field("encoding", s.encoding.label).field("byteLength", s.byteLength)
            j.arrayField("categories"); s.categories.sortedBy { it.ordinal }.forEach { j.value(it.name) }; j.endArray()
            j.field("text", s.text).endObject()
        }
        j.endArray().endObject()
        return j.toString()
    }

    /** Makes control characters visible in single-line text output. */
    fun escape(s: String): String = buildString {
        for (c in s) when (c) { '\t' -> append("\\t"); '\n' -> append("\\n"); '\r' -> append("\\r"); else -> append(c) }
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
