package com.nativelib.restudio.core.elf

/** Thrown when data is not a usable ELF file (bad magic, truncated header, impossible geometry). */
class ElfFormatException(message: String) : Exception(message)

enum class ElfClass(val bits: Int) { ELF32(32), ELF64(64) }
enum class Endianness { LITTLE, BIG }

data class ElfIdent(
    val elfClass: ElfClass,
    val endianness: Endianness,
    val version: Int,
    val osAbi: Int,
    val abiVersion: Int,
) {
    val osAbiName: String get() = ElfConstants.osAbiName(osAbi)
}

data class ElfHeader(
    val ident: ElfIdent,
    val type: Int,
    val machine: Int,
    val version: Long,
    val entry: Long,
    val phOff: Long,
    val shOff: Long,
    val flags: Long,
    val ehSize: Int,
    val phEntSize: Int,
    /** Real program header count (extended numbering resolved). */
    val phNum: Int,
    val shEntSize: Int,
    /** Real section header count (extended numbering resolved). */
    val shNum: Int,
    /** Real section-name string table index (extended numbering resolved). */
    val shStrNdx: Int,
) {
    val typeName: String get() = ElfConstants.typeName(type)
    val machineName: String get() = ElfConstants.machineName(machine)
    val is64: Boolean get() = ident.elfClass == ElfClass.ELF64
}

data class ProgramHeader(
    val index: Int,
    val type: Long,
    val flags: Long,
    val offset: Long,
    val vaddr: Long,
    val paddr: Long,
    val fileSize: Long,
    val memSize: Long,
    val align: Long,
) {
    val typeName: String get() = ElfConstants.segmentTypeName(type)
    val isReadable get() = flags and ElfConstants.PF_R != 0L
    val isWritable get() = flags and ElfConstants.PF_W != 0L
    val isExecutable get() = flags and ElfConstants.PF_X != 0L
    val permissions: String get() = (if (isReadable) "R" else "-") + (if (isWritable) "W" else "-") + (if (isExecutable) "X" else "-")
}

data class SectionHeader(
    val index: Int,
    val name: String,
    val nameOffset: Long,
    val type: Long,
    val flags: Long,
    val addr: Long,
    val offset: Long,
    val size: Long,
    val link: Long,
    val info: Long,
    val addrAlign: Long,
    val entSize: Long,
) {
    val typeName: String get() = ElfConstants.sectionTypeName(type)
    val flagsString: String get() = ElfConstants.sectionFlagsString(flags)
    /** SHT_NOBITS sections (.bss) occupy no bytes in the file. */
    val occupiesFile: Boolean get() = type != ElfConstants.SHT_NOBITS && type != ElfConstants.SHT_NULL
}

data class DynamicEntry(val tag: Long, val value: Long, val stringValue: String? = null) {
    val tagName: String get() = ElfConstants.dynamicTagName(tag)
}

enum class SymbolTableKind { DYNSYM, SYMTAB }

data class ElfSymbol(
    val index: Int,
    val table: SymbolTableKind,
    val name: String,
    val value: Long,
    val size: Long,
    val bind: Int,
    val type: Int,
    val visibility: Int,
    val sectionIndex: Int,
    /** GNU symbol version (e.g. "LIBC", "LIBSAMPLE_1.0"), null when no version info exists. */
    val version: String? = null,
    /** True when the version is hidden (non-default, "@" rather than "@@"). */
    val versionHidden: Boolean = false,
) {
    val bindName: String get() = ElfConstants.symbolBindName(bind)
    val typeName: String get() = ElfConstants.symbolTypeName(type)
    val visibilityName: String get() = ElfConstants.symbolVisibilityName(visibility)
    val isUndefined: Boolean get() = sectionIndex == ElfConstants.SHN_UNDEF
    val isFunction: Boolean get() = type == ElfConstants.STT_FUNC || type == ElfConstants.STT_GNU_IFUNC
    val isObject: Boolean get() = type == ElfConstants.STT_OBJECT || type == ElfConstants.STT_TLS
    val isGlobal: Boolean get() = bind == ElfConstants.STB_GLOBAL
    val isWeak: Boolean get() = bind == ElfConstants.STB_WEAK
    val isLocal: Boolean get() = bind == ElfConstants.STB_LOCAL
    /** Defined, globally visible symbol that other modules can link against. */
    val isExported: Boolean get() = !isUndefined && (isGlobal || isWeak || bind == ElfConstants.STB_GNU_UNIQUE) &&
        (visibility == ElfConstants.STV_DEFAULT || visibility == ElfConstants.STV_PROTECTED) && name.isNotEmpty()
    /** Undefined named symbol resolved from another module at load time. */
    val isImported: Boolean get() = isUndefined && name.isNotEmpty() && table == SymbolTableKind.DYNSYM
    val sectionIndexName: String get() = when (sectionIndex) {
        ElfConstants.SHN_UNDEF -> "UND"; ElfConstants.SHN_ABS -> "ABS"; ElfConstants.SHN_COMMON -> "COM"
        else -> sectionIndex.toString()
    }
    val displayName: String get() = if (version == null) name else name + (if (versionHidden) "@" else "@@") + version
}

data class Relocation(
    val sectionName: String,
    val offset: Long,
    val type: Long,
    val typeName: String,
    val symbolIndex: Long,
    val symbolName: String?,
    /** Explicit addend for RELA; null for REL. */
    val addend: Long?,
)

data class ElfNote(val sectionOrSegment: String, val owner: String, val type: Long, val descriptor: ByteArray) {
    val typeName: String get() = ElfConstants.noteTypeName(owner, type)
    override fun equals(other: Any?) = other is ElfNote && other.owner == owner && other.type == type && other.descriptor.contentEquals(descriptor)
    override fun hashCode() = owner.hashCode() * 31 + type.hashCode()
}

data class VersionNeed(val file: String, val versions: List<String>)

/** Hardening-related properties derived from headers. These are facts, not verdicts. */
data class SecurityProperties(
    val hasRwxSegment: Boolean,
    val nxStack: Boolean?,          // null when PT_GNU_STACK absent
    val relro: RelroLevel,
    val bindNow: Boolean,
    val hasTextRelocations: Boolean,
    val isPie: Boolean,
    val maxLoadAlignment: Long,
    /** Android 15+ devices with 16 KiB pages require LOAD alignment >= 16 KiB. */
    val supports16KbPages: Boolean,
)

enum class RelroLevel { NONE, PARTIAL, FULL }

enum class WarningSeverity { INFO, WARNING, ERROR }

data class ElfWarning(val severity: WarningSeverity, val message: String)

data class ElfFile(
    val fileSize: Long,
    val header: ElfHeader,
    val programHeaders: List<ProgramHeader>,
    val sections: List<SectionHeader>,
    val dynamic: List<DynamicEntry>,
    val dynamicSymbols: List<ElfSymbol>,
    val staticSymbols: List<ElfSymbol>,
    val relocations: List<Relocation>,
    val notes: List<ElfNote>,
    val versionNeeds: List<VersionNeed>,
    val versionDefinitions: List<String>,
    val security: SecurityProperties,
    val warnings: List<ElfWarning>,
) {
    val buildId: String? get() = notes.firstOrNull { it.owner == "GNU" && it.type == ElfConstants.NT_GNU_BUILD_ID }
        ?.descriptor?.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    val neededLibraries: List<String> get() = dynamic.filter { it.tag == ElfConstants.DT_NEEDED }.mapNotNull { it.stringValue }
    val soname: String? get() = dynamic.firstOrNull { it.tag == ElfConstants.DT_SONAME }?.stringValue
    val rpath: String? get() = dynamic.firstOrNull { it.tag == ElfConstants.DT_RPATH }?.stringValue
    val runpath: String? get() = dynamic.firstOrNull { it.tag == ElfConstants.DT_RUNPATH }?.stringValue

    /** True when there is no .symtab — only dynamic symbols are available. */
    val isStripped: Boolean get() = sections.none { it.type == ElfConstants.SHT_SYMTAB }
    val hasDebugInfo: Boolean get() = sections.any { it.name.startsWith(".debug_") || it.name.startsWith(".zdebug_") }

    val exportedSymbols: List<ElfSymbol> get() = dynamicSymbols.filter { it.isExported }
    val importedSymbols: List<ElfSymbol> get() = dynamicSymbols.filter { it.isImported }
    val allSymbols: List<ElfSymbol> get() = dynamicSymbols + staticSymbols

    fun sectionByName(name: String): SectionHeader? = sections.firstOrNull { it.name == name }

    /** Human-readable explanation of what symbol information is NOT available. */
    fun symbolAvailabilityNotes(): List<String> = buildList {
        if (isStripped) add("No .symtab section: the binary is stripped. Local/static symbols and names of internal functions are unavailable; only dynamic (exported/imported) symbols can be shown.")
        if (dynamicSymbols.isEmpty()) add("No .dynsym symbols found: exported and imported symbol information is unavailable.")
        if (!hasDebugInfo) add("No DWARF debug sections: source file names, line numbers, types and parameter names are unavailable.")
        if (versionNeeds.isEmpty() && versionDefinitions.isEmpty()) add("No GNU symbol versioning sections present.")
    }
}
