package com.nativelib.restudio.core.archive

enum class ContainerType(val label: String) {
    APK("APK"), XAPK("XAPK"), APKS("APKS / split APK set"), AAB("Android App Bundle (AAB)"),
    JAR("JAR"), ZIP("ZIP"), DEX("DEX"), ELF("ELF / shared object"), UNKNOWN("Unknown / raw binary");

    val isArchive: Boolean get() = this == APK || this == XAPK || this == APKS || this == AAB || this == JAR || this == ZIP
}

class ArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Abis {
    /** ABIs that current Android versions can load. */
    val SUPPORTED = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64", "riscv64")
    /** ABIs that once existed but are no longer supported by Android (NDK r17+ removed them). */
    val DEPRECATED = listOf("mips", "mips64")

    fun isKnown(abi: String) = abi in SUPPORTED || abi in DEPRECATED

    /** Whether an ELF with this Android ABI (derived from e_machine/class) is valid inside folder [dirAbi]. */
    fun matches(dirAbi: String, elfAbi: String?): Boolean = when {
        elfAbi == null -> false
        dirAbi == elfAbi -> true
        dirAbi == "armeabi" && elfAbi == "armeabi-v7a" -> true // both are EM_ARM ELF32
        else -> false
    }
}

enum class CompressionMethod { STORED, DEFLATED, OTHER }

/** A native library found inside a container, before extraction. */
data class NativeLibEntry(
    /** Path of the nested container (e.g. "split_config.arm64_v8a.apk"), or "" for the top-level archive. */
    val container: String,
    /** Entry path inside its container, e.g. "lib/arm64-v8a/libfoo.so". */
    val entryPath: String,
    /** ABI directory name, or null when the .so is outside a standard lib/<abi>/ directory. */
    val abi: String?,
    val fileName: String,
    val size: Long,
    val compressedSize: Long,
    val crc: Long,
    val method: CompressionMethod,
) {
    val isStandardLocation: Boolean get() = abi != null
    val displayPath: String get() = if (container.isEmpty()) entryPath else "$container!/$entryPath"
}

data class ElfSummary(
    val machine: String,
    val bits: Int,
    val elfAbi: String?,
    val soname: String?,
    val needed: List<String>,
    val exportedCount: Int,
    val importedCount: Int,
    val stripped: Boolean,
    val buildId: String?,
    val supports16KbPages: Boolean,
    val parserErrors: Int,
)

enum class IssueType(val label: String) {
    CORRUPTED("Corrupted"), NOT_ELF("Not a valid ELF"), ABI_MISMATCH("ABI mismatch"), MISSING_IN_ABI("Missing in ABI"),
    DUPLICATE("Duplicate"), IDENTICAL_ACROSS_ABIS("Identical across ABIs"), UNSUPPORTED_ABI("Unsupported ABI"),
    NON_STANDARD_LOCATION("Non-standard location"), UNSAFE_PATH("Unsafe path"), TOO_LARGE("Too large"),
    NESTED_CONTAINER_ERROR("Nested container error"), NOT_16KB_ALIGNED("Not 16 KiB page aligned");
}

enum class IssueSeverity { INFO, WARNING, ERROR }

data class Issue(val type: IssueType, val severity: IssueSeverity, val message: String, val path: String? = null)

data class ExtractedLib(
    val entry: NativeLibEntry,
    /** Path of the extracted file relative to the extraction root (null when extraction failed). */
    val outputRelativePath: String?,
    val sha256: String?,
    /** SHA-256 recomputed from the written file equals the hash computed while streaming. */
    val hashVerified: Boolean,
    /** CRC-32 of the extracted bytes equals the CRC recorded in the ZIP directory. */
    val crcVerified: Boolean,
    val elf: ElfSummary?,
)

data class ExtractionReport(
    val sourceName: String,
    val sourceSize: Long,
    val sourceSha256: String,
    val containerType: ContainerType,
    val nestedContainers: List<String>,
    val abis: List<String>,
    val libraries: List<ExtractedLib>,
    val issues: List<Issue>,
    val totalEntries: Int,
    val durationMs: Long,
) {
    val extractedCount: Int get() = libraries.count { it.outputRelativePath != null }
    val verifiedCount: Int get() = libraries.count { it.hashVerified && it.crcVerified }
    fun librariesFor(abi: String) = libraries.filter { it.entry.abi == abi }
}

fun interface ProgressListener {
    fun onProgress(done: Int, total: Int, current: String)
}
