package com.nativelib.restudio.update

/** Pure, Android-free rules for self-update decisions (unit tested on the JVM). */
object UpdateLogic {
    const val REPO = "faria2177/NativeLibREStudio"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"

    /** CI tags releases as "build-<GITHUB_RUN_NUMBER>", and versionCode == GITHUB_RUN_NUMBER. */
    fun buildNumberFromTag(tag: String): Int? = Regex("""^build-(\d+)$""").matchEntire(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun isNewer(tag: String, installedVersionCode: Long): Boolean {
        val n = buildNumberFromTag(tag) ?: return false
        return n > installedVersionCode
    }

    /** Debug installs (package ends with ".debug") update from the debug APK, release installs from the release APK. */
    fun assetSuffixFor(packageName: String): String = if (packageName.endsWith(".debug")) "-debug.apk" else "-release.apk"

    fun chooseApk(assetNames: List<String>, packageName: String): String? {
        val suffix = assetSuffixFor(packageName)
        return assetNames.firstOrNull { it.endsWith(suffix) }
    }

    /** Parses `sha256sum` output ("<hex>  <path>") into fileName -> lowercase hash. */
    fun parseSha256Sums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val m = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""").matchEntire(line.trim()) ?: return@mapNotNull null
        m.groupValues[2].substringAfterLast('/') to m.groupValues[1].lowercase()
    }.toMap()
}
