package com.nativelib.restudio.core.elf

/** Constants and name tables from the System V gABI, the GNU extensions and processor supplements. */
object ElfConstants {
    // e_type
    const val ET_NONE = 0; const val ET_REL = 1; const val ET_EXEC = 2; const val ET_DYN = 3; const val ET_CORE = 4

    // e_machine
    const val EM_386 = 3; const val EM_MIPS = 8; const val EM_ARM = 40; const val EM_X86_64 = 62
    const val EM_AARCH64 = 183; const val EM_RISCV = 243

    // special section indices
    const val SHN_UNDEF = 0; const val SHN_LORESERVE = 0xff00; const val SHN_ABS = 0xfff1
    const val SHN_COMMON = 0xfff2; const val SHN_XINDEX = 0xffff
    const val PN_XNUM = 0xffff

    // section types
    const val SHT_NULL = 0L; const val SHT_PROGBITS = 1L; const val SHT_SYMTAB = 2L; const val SHT_STRTAB = 3L
    const val SHT_RELA = 4L; const val SHT_HASH = 5L; const val SHT_DYNAMIC = 6L; const val SHT_NOTE = 7L
    const val SHT_NOBITS = 8L; const val SHT_REL = 9L; const val SHT_DYNSYM = 11L
    const val SHT_INIT_ARRAY = 14L; const val SHT_FINI_ARRAY = 15L; const val SHT_PREINIT_ARRAY = 16L
    const val SHT_GROUP = 17L; const val SHT_SYMTAB_SHNDX = 18L; const val SHT_RELR = 19L
    const val SHT_ANDROID_REL = 0x60000001L; const val SHT_ANDROID_RELA = 0x60000002L
    const val SHT_ANDROID_RELR = 0x6fffff00L
    const val SHT_GNU_ATTRIBUTES = 0x6ffffff5L; const val SHT_GNU_HASH = 0x6ffffff6L
    const val SHT_GNU_VERDEF = 0x6ffffffdL; const val SHT_GNU_VERNEED = 0x6ffffffeL; const val SHT_GNU_VERSYM = 0x6fffffffL
    const val SHT_ARM_EXIDX = 0x70000001L; const val SHT_ARM_ATTRIBUTES = 0x70000003L

    // section flags
    const val SHF_WRITE = 0x1L; const val SHF_ALLOC = 0x2L; const val SHF_EXECINSTR = 0x4L; const val SHF_MERGE = 0x10L
    const val SHF_STRINGS = 0x20L; const val SHF_INFO_LINK = 0x40L; const val SHF_LINK_ORDER = 0x80L
    const val SHF_GROUP = 0x200L; const val SHF_TLS = 0x400L; const val SHF_COMPRESSED = 0x800L

    // segment types
    const val PT_NULL = 0L; const val PT_LOAD = 1L; const val PT_DYNAMIC = 2L; const val PT_INTERP = 3L
    const val PT_NOTE = 4L; const val PT_SHLIB = 5L; const val PT_PHDR = 6L; const val PT_TLS = 7L
    const val PT_GNU_EH_FRAME = 0x6474e550L; const val PT_GNU_STACK = 0x6474e551L; const val PT_GNU_RELRO = 0x6474e552L
    const val PT_GNU_PROPERTY = 0x6474e553L; const val PT_ARM_EXIDX = 0x70000001L
    const val PF_X = 1L; const val PF_W = 2L; const val PF_R = 4L

    // dynamic tags
    const val DT_NULL = 0L; const val DT_NEEDED = 1L; const val DT_PLTRELSZ = 2L; const val DT_PLTGOT = 3L
    const val DT_HASH = 4L; const val DT_STRTAB = 5L; const val DT_SYMTAB = 6L; const val DT_RELA = 7L
    const val DT_RELASZ = 8L; const val DT_RELAENT = 9L; const val DT_STRSZ = 10L; const val DT_SYMENT = 11L
    const val DT_INIT = 12L; const val DT_FINI = 13L; const val DT_SONAME = 14L; const val DT_RPATH = 15L
    const val DT_SYMBOLIC = 16L; const val DT_REL = 17L; const val DT_RELSZ = 18L; const val DT_RELENT = 19L
    const val DT_PLTREL = 20L; const val DT_DEBUG = 21L; const val DT_TEXTREL = 22L; const val DT_JMPREL = 23L
    const val DT_BIND_NOW = 24L; const val DT_INIT_ARRAY = 25L; const val DT_FINI_ARRAY = 26L
    const val DT_INIT_ARRAYSZ = 27L; const val DT_FINI_ARRAYSZ = 28L; const val DT_RUNPATH = 29L
    const val DT_FLAGS = 30L; const val DT_PREINIT_ARRAY = 32L; const val DT_PREINIT_ARRAYSZ = 33L
    const val DT_SYMTAB_SHNDX = 34L; const val DT_RELRSZ = 35L; const val DT_RELR = 36L; const val DT_RELRENT = 37L
    const val DT_ANDROID_REL = 0x6000000fL; const val DT_ANDROID_RELSZ = 0x60000010L
    const val DT_ANDROID_RELA = 0x60000011L; const val DT_ANDROID_RELASZ = 0x60000012L
    const val DT_ANDROID_RELR = 0x6fffe000L; const val DT_ANDROID_RELRSZ = 0x6fffe001L
    const val DT_ANDROID_RELRENT = 0x6fffe003L
    const val DT_GNU_HASH = 0x6ffffef5L; const val DT_VERSYM = 0x6ffffff0L; const val DT_RELACOUNT = 0x6ffffff9L
    const val DT_RELCOUNT = 0x6ffffffaL; const val DT_FLAGS_1 = 0x6ffffffbL; const val DT_VERDEF = 0x6ffffffcL
    const val DT_VERDEFNUM = 0x6ffffffdL; const val DT_VERNEED = 0x6ffffffeL; const val DT_VERNEEDNUM = 0x6fffffffL
    const val DF_BIND_NOW = 0x8L; const val DF_TEXTREL = 0x4L; const val DF_1_NOW = 0x1L; const val DF_1_PIE = 0x08000000L

    // symbols
    const val STB_LOCAL = 0; const val STB_GLOBAL = 1; const val STB_WEAK = 2; const val STB_GNU_UNIQUE = 10
    const val STT_NOTYPE = 0; const val STT_OBJECT = 1; const val STT_FUNC = 2; const val STT_SECTION = 3
    const val STT_FILE = 4; const val STT_COMMON = 5; const val STT_TLS = 6; const val STT_GNU_IFUNC = 10
    const val STV_DEFAULT = 0; const val STV_INTERNAL = 1; const val STV_HIDDEN = 2; const val STV_PROTECTED = 3

    // notes
    const val NT_GNU_ABI_TAG = 1L; const val NT_GNU_HWCAP = 2L; const val NT_GNU_BUILD_ID = 3L
    const val NT_GNU_GOLD_VERSION = 4L; const val NT_GNU_PROPERTY_TYPE_0 = 5L

    fun typeName(t: Int) = when (t) {
        ET_NONE -> "NONE"; ET_REL -> "REL (Relocatable)"; ET_EXEC -> "EXEC (Executable)"
        ET_DYN -> "DYN (Shared object / PIE)"; ET_CORE -> "CORE"; else -> "UNKNOWN (0x%x)".format(t)
    }

    fun machineName(m: Int) = when (m) {
        0 -> "None"; 2 -> "SPARC"; EM_386 -> "Intel 80386 (x86)"; EM_MIPS -> "MIPS"; 20 -> "PowerPC"; 21 -> "PowerPC64"
        EM_ARM -> "ARM (32-bit)"; 50 -> "IA-64"; EM_X86_64 -> "AMD x86-64"; EM_AARCH64 -> "AArch64 (ARM64)"
        EM_RISCV -> "RISC-V"; 247 -> "BPF"; 258 -> "LoongArch"; else -> "Unknown (%d)".format(m)
    }

    /** Android ABI directory name matching an ELF machine/class pair, or null if Android has no such ABI. */
    fun androidAbiFor(machine: Int, is64: Boolean): String? = when {
        machine == EM_AARCH64 && is64 -> "arm64-v8a"
        machine == EM_ARM && !is64 -> "armeabi-v7a" // also valid in legacy "armeabi"
        machine == EM_X86_64 && is64 -> "x86_64"
        machine == EM_386 && !is64 -> "x86"
        machine == EM_RISCV && is64 -> "riscv64"
        machine == EM_MIPS && !is64 -> "mips"
        machine == EM_MIPS && is64 -> "mips64"
        else -> null
    }

    fun osAbiName(v: Int) = when (v) {
        0 -> "UNIX - System V"; 3 -> "Linux (GNU)"; 6 -> "Solaris"; 9 -> "FreeBSD"; 97 -> "ARM"; 255 -> "Standalone"
        else -> "Unknown (%d)".format(v)
    }

    fun segmentTypeName(t: Long) = when (t) {
        PT_NULL -> "NULL"; PT_LOAD -> "LOAD"; PT_DYNAMIC -> "DYNAMIC"; PT_INTERP -> "INTERP"; PT_NOTE -> "NOTE"
        PT_SHLIB -> "SHLIB"; PT_PHDR -> "PHDR"; PT_TLS -> "TLS"; PT_GNU_EH_FRAME -> "GNU_EH_FRAME"
        PT_GNU_STACK -> "GNU_STACK"; PT_GNU_RELRO -> "GNU_RELRO"; PT_GNU_PROPERTY -> "GNU_PROPERTY"
        PT_ARM_EXIDX -> "ARM_EXIDX"; else -> "0x%x".format(t)
    }

    fun sectionTypeName(t: Long) = when (t) {
        SHT_NULL -> "NULL"; SHT_PROGBITS -> "PROGBITS"; SHT_SYMTAB -> "SYMTAB"; SHT_STRTAB -> "STRTAB"
        SHT_RELA -> "RELA"; SHT_HASH -> "HASH"; SHT_DYNAMIC -> "DYNAMIC"; SHT_NOTE -> "NOTE"; SHT_NOBITS -> "NOBITS"
        SHT_REL -> "REL"; 10L -> "SHLIB"; SHT_DYNSYM -> "DYNSYM"; SHT_INIT_ARRAY -> "INIT_ARRAY"
        SHT_FINI_ARRAY -> "FINI_ARRAY"; SHT_PREINIT_ARRAY -> "PREINIT_ARRAY"; SHT_GROUP -> "GROUP"
        SHT_SYMTAB_SHNDX -> "SYMTAB_SHNDX"; SHT_RELR -> "RELR"; SHT_ANDROID_REL -> "ANDROID_REL"
        SHT_ANDROID_RELA -> "ANDROID_RELA"; SHT_ANDROID_RELR -> "ANDROID_RELR"; SHT_GNU_ATTRIBUTES -> "GNU_ATTRIBUTES"
        SHT_GNU_HASH -> "GNU_HASH"; SHT_GNU_VERDEF -> "VERDEF"; SHT_GNU_VERNEED -> "VERNEED"; SHT_GNU_VERSYM -> "VERSYM"
        SHT_ARM_EXIDX -> "ARM_EXIDX"; SHT_ARM_ATTRIBUTES -> "ARM_ATTRIBUTES"; 0x6fff4c00L -> "LLVM_ODRTAB"
        0x6fff4c01L -> "LLVM_LINKER_OPTIONS"; 0x6fff4c03L -> "LLVM_ADDRSIG"; else -> "0x%x".format(t)
    }

    fun sectionFlagsString(f: Long): String = buildString {
        if (f and SHF_WRITE != 0L) append('W'); if (f and SHF_ALLOC != 0L) append('A')
        if (f and SHF_EXECINSTR != 0L) append('X'); if (f and SHF_MERGE != 0L) append('M')
        if (f and SHF_STRINGS != 0L) append('S'); if (f and SHF_INFO_LINK != 0L) append('I')
        if (f and SHF_LINK_ORDER != 0L) append('L'); if (f and SHF_GROUP != 0L) append('G')
        if (f and SHF_TLS != 0L) append('T'); if (f and SHF_COMPRESSED != 0L) append('C')
    }

    fun dynamicTagName(t: Long) = when (t) {
        DT_NULL -> "NULL"; DT_NEEDED -> "NEEDED"; DT_PLTRELSZ -> "PLTRELSZ"; DT_PLTGOT -> "PLTGOT"; DT_HASH -> "HASH"
        DT_STRTAB -> "STRTAB"; DT_SYMTAB -> "SYMTAB"; DT_RELA -> "RELA"; DT_RELASZ -> "RELASZ"; DT_RELAENT -> "RELAENT"
        DT_STRSZ -> "STRSZ"; DT_SYMENT -> "SYMENT"; DT_INIT -> "INIT"; DT_FINI -> "FINI"; DT_SONAME -> "SONAME"
        DT_RPATH -> "RPATH"; DT_SYMBOLIC -> "SYMBOLIC"; DT_REL -> "REL"; DT_RELSZ -> "RELSZ"; DT_RELENT -> "RELENT"
        DT_PLTREL -> "PLTREL"; DT_DEBUG -> "DEBUG"; DT_TEXTREL -> "TEXTREL"; DT_JMPREL -> "JMPREL"
        DT_BIND_NOW -> "BIND_NOW"; DT_INIT_ARRAY -> "INIT_ARRAY"; DT_FINI_ARRAY -> "FINI_ARRAY"
        DT_INIT_ARRAYSZ -> "INIT_ARRAYSZ"; DT_FINI_ARRAYSZ -> "FINI_ARRAYSZ"; DT_RUNPATH -> "RUNPATH"
        DT_FLAGS -> "FLAGS"; DT_PREINIT_ARRAY -> "PREINIT_ARRAY"; DT_PREINIT_ARRAYSZ -> "PREINIT_ARRAYSZ"
        DT_SYMTAB_SHNDX -> "SYMTAB_SHNDX"; DT_RELRSZ -> "RELRSZ"; DT_RELR -> "RELR"; DT_RELRENT -> "RELRENT"
        DT_ANDROID_REL -> "ANDROID_REL"; DT_ANDROID_RELSZ -> "ANDROID_RELSZ"; DT_ANDROID_RELA -> "ANDROID_RELA"
        DT_ANDROID_RELASZ -> "ANDROID_RELASZ"; DT_ANDROID_RELR -> "ANDROID_RELR"; DT_ANDROID_RELRSZ -> "ANDROID_RELRSZ"
        DT_ANDROID_RELRENT -> "ANDROID_RELRENT"; DT_GNU_HASH -> "GNU_HASH"; DT_VERSYM -> "VERSYM"
        DT_RELACOUNT -> "RELACOUNT"; DT_RELCOUNT -> "RELCOUNT"; DT_FLAGS_1 -> "FLAGS_1"; DT_VERDEF -> "VERDEF"
        DT_VERDEFNUM -> "VERDEFNUM"; DT_VERNEED -> "VERNEED"; DT_VERNEEDNUM -> "VERNEEDNUM"
        else -> "0x%x".format(t)
    }

    /** Dynamic tags whose value is an offset into the dynamic string table. */
    fun dynamicTagIsString(t: Long) = t == DT_NEEDED || t == DT_SONAME || t == DT_RPATH || t == DT_RUNPATH

    fun symbolBindName(b: Int) = when (b) {
        STB_LOCAL -> "LOCAL"; STB_GLOBAL -> "GLOBAL"; STB_WEAK -> "WEAK"; STB_GNU_UNIQUE -> "UNIQUE"; else -> "BIND_$b"
    }

    fun symbolTypeName(t: Int) = when (t) {
        STT_NOTYPE -> "NOTYPE"; STT_OBJECT -> "OBJECT"; STT_FUNC -> "FUNC"; STT_SECTION -> "SECTION"; STT_FILE -> "FILE"
        STT_COMMON -> "COMMON"; STT_TLS -> "TLS"; STT_GNU_IFUNC -> "IFUNC"; else -> "TYPE_$t"
    }

    fun symbolVisibilityName(v: Int) = when (v) {
        STV_DEFAULT -> "DEFAULT"; STV_INTERNAL -> "INTERNAL"; STV_HIDDEN -> "HIDDEN"; STV_PROTECTED -> "PROTECTED"; else -> "VIS_$v"
    }

    fun noteTypeName(owner: String, type: Long): String = when (owner) {
        "GNU" -> when (type) {
            NT_GNU_ABI_TAG -> "NT_GNU_ABI_TAG"; NT_GNU_HWCAP -> "NT_GNU_HWCAP"; NT_GNU_BUILD_ID -> "NT_GNU_BUILD_ID"
            NT_GNU_GOLD_VERSION -> "NT_GNU_GOLD_VERSION"; NT_GNU_PROPERTY_TYPE_0 -> "NT_GNU_PROPERTY_TYPE_0"
            else -> "0x%x".format(type)
        }
        "Android" -> when (type) { 1L -> "NT_ANDROID_TYPE_IDENT"; 3L -> "NT_ANDROID_TYPE_KUSER"; 4L -> "NT_ANDROID_TYPE_MEMTAG"; else -> "0x%x".format(type) }
        else -> "0x%x".format(type)
    }

    fun relocationTypeName(machine: Int, type: Long): String {
        val t = type.toInt()
        val n: String? = when (machine) {
            EM_AARCH64 -> when (t) {
                0 -> "R_AARCH64_NONE"; 257 -> "R_AARCH64_ABS64"; 258 -> "R_AARCH64_ABS32"; 260 -> "R_AARCH64_PREL64"
                261 -> "R_AARCH64_PREL32"; 1024 -> "R_AARCH64_COPY"; 1025 -> "R_AARCH64_GLOB_DAT"
                1026 -> "R_AARCH64_JUMP_SLOT"; 1027 -> "R_AARCH64_RELATIVE"; 1028 -> "R_AARCH64_TLS_DTPMOD64"
                1029 -> "R_AARCH64_TLS_DTPREL64"; 1030 -> "R_AARCH64_TLS_TPREL64"; 1031 -> "R_AARCH64_TLSDESC"
                1032 -> "R_AARCH64_IRELATIVE"; 275 -> "R_AARCH64_ADR_PREL_PG_HI21"; 277 -> "R_AARCH64_ADD_ABS_LO12_NC"
                282 -> "R_AARCH64_JUMP26"; 283 -> "R_AARCH64_CALL26"; 286 -> "R_AARCH64_LDST64_ABS_LO12_NC"
                311 -> "R_AARCH64_ADR_GOT_PAGE"; 312 -> "R_AARCH64_LD64_GOT_LO12_NC"; else -> null
            }
            EM_ARM -> when (t) {
                0 -> "R_ARM_NONE"; 2 -> "R_ARM_ABS32"; 3 -> "R_ARM_REL32"; 10 -> "R_ARM_THM_CALL"; 17 -> "R_ARM_TLS_DTPMOD32"
                18 -> "R_ARM_TLS_DTPOFF32"; 19 -> "R_ARM_TLS_TPOFF32"; 20 -> "R_ARM_COPY"; 21 -> "R_ARM_GLOB_DAT"
                22 -> "R_ARM_JUMP_SLOT"; 23 -> "R_ARM_RELATIVE"; 28 -> "R_ARM_CALL"; 29 -> "R_ARM_JUMP24"
                30 -> "R_ARM_THM_JUMP24"; 42 -> "R_ARM_PREL31"; 43 -> "R_ARM_MOVW_ABS_NC"; 44 -> "R_ARM_MOVT_ABS"
                160 -> "R_ARM_IRELATIVE"; else -> null
            }
            EM_X86_64 -> when (t) {
                0 -> "R_X86_64_NONE"; 1 -> "R_X86_64_64"; 2 -> "R_X86_64_PC32"; 3 -> "R_X86_64_GOT32"; 4 -> "R_X86_64_PLT32"
                5 -> "R_X86_64_COPY"; 6 -> "R_X86_64_GLOB_DAT"; 7 -> "R_X86_64_JUMP_SLOT"; 8 -> "R_X86_64_RELATIVE"
                9 -> "R_X86_64_GOTPCREL"; 10 -> "R_X86_64_32"; 11 -> "R_X86_64_32S"; 16 -> "R_X86_64_DTPMOD64"
                17 -> "R_X86_64_DTPOFF64"; 18 -> "R_X86_64_TPOFF64"; 24 -> "R_X86_64_PC64"; 37 -> "R_X86_64_IRELATIVE"
                41 -> "R_X86_64_GOTPCRELX"; 42 -> "R_X86_64_REX_GOTPCRELX"; else -> null
            }
            EM_386 -> when (t) {
                0 -> "R_386_NONE"; 1 -> "R_386_32"; 2 -> "R_386_PC32"; 3 -> "R_386_GOT32"; 4 -> "R_386_PLT32"
                5 -> "R_386_COPY"; 6 -> "R_386_GLOB_DAT"; 7 -> "R_386_JMP_SLOT"; 8 -> "R_386_RELATIVE"
                9 -> "R_386_GOTOFF"; 10 -> "R_386_GOTPC"; 14 -> "R_386_TLS_TPOFF"; 35 -> "R_386_TLS_DTPMOD32"
                36 -> "R_386_TLS_DTPOFF32"; 42 -> "R_386_IRELATIVE"; 43 -> "R_386_GOT32X"; else -> null
            }
            EM_RISCV -> when (t) {
                0 -> "R_RISCV_NONE"; 1 -> "R_RISCV_32"; 2 -> "R_RISCV_64"; 3 -> "R_RISCV_RELATIVE"; 4 -> "R_RISCV_COPY"
                5 -> "R_RISCV_JUMP_SLOT"; 6 -> "R_RISCV_TLS_DTPMOD32"; 7 -> "R_RISCV_TLS_DTPMOD64"; 58 -> "R_RISCV_IRELATIVE"
                else -> null
            }
            else -> null
        }
        return n ?: "TYPE_%d".format(t)
    }
}
