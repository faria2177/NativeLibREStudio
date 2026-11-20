package com.nativelib.restudio.core.elf

import com.nativelib.restudio.core.common.JsonWriter
import com.nativelib.restudio.core.common.toHexAddr

/** Export of a (possibly filtered) symbol list. Same input ⇒ identical output. */
object SymbolReport {
    private const val HEADER = "table,index,name,version,value,size,type,bind,visibility,section,exported,imported"

    fun toCsv(symbols: List<ElfSymbol>): String = buildString {
        appendLine(HEADER)
        symbols.forEach { s ->
            appendLine(listOf(s.table.name.lowercase(), s.index.toString(), s.name, s.version ?: "", s.value.toHexAddr(), s.size.toString(),
                s.typeName, s.bindName, s.visibilityName, s.sectionIndexName, s.isExported.toString(), s.isImported.toString()).joinToString(",") { csv(it) })
        }
    }

    fun toJson(title: String, symbols: List<ElfSymbol>, notes: List<String>): String {
        val j = JsonWriter()
        j.beginObject().field("file", title).field("count", symbols.size)
        j.arrayField("availabilityNotes"); notes.forEach { j.value(it) }; j.endArray()
        j.arrayField("symbols")
        symbols.forEach { s ->
            j.beginObject().field("table", s.table.name.lowercase()).field("index", s.index).field("name", s.name).field("version", s.version)
                .field("value", s.value.toHexAddr()).field("size", s.size).field("type", s.typeName).field("bind", s.bindName)
                .field("visibility", s.visibilityName).field("section", s.sectionIndexName)
                .field("exported", s.isExported).field("imported", s.isImported).endObject()
        }
        j.endArray().endObject()
        return j.toString()
    }

    fun toText(title: String, symbols: List<ElfSymbol>, notes: List<String>, is64: Boolean): String = buildString {
        val w = if (is64) 16 else 8
        appendLine("SYMBOLS — $title (${symbols.size})")
        notes.forEach { appendLine("NOTE: $it") }
        appendLine()
        symbols.forEach { s ->
            appendLine("%-6s %-${w + 2}s %8d %-6s %-7s %-9s %-5s %s".format(s.table.name.lowercase(), s.value.toHexAddr(w), s.size, s.typeName,
                s.bindName, s.visibilityName, s.sectionIndexName, s.displayName))
        }
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
