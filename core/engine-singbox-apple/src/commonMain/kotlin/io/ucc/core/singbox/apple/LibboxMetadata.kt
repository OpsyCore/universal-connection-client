package io.ucc.core.singbox.apple

/**
 * Pinned identity of the Apple core. Kept in code so the extension can report it
 * through IPC/diagnostics, and cross-checked by a JVM test against the repository
 * pin files (`core/engine-singbox/singbox.version`, `singbox.commit`, `libbox-apple.sha256`).
 *
 * [xcframeworkSha256] is `null` = UNRESOLVED: no Libbox.xcframework has been retained
 * (GitHub Actions artifact storage is blocked on the account). It must only ever be set
 * to the hash of a real, retained framework — never guessed.
 */
public object LibboxMetadata {
    public const val SING_BOX_VERSION: String = "v1.13.21"
    public const val SING_BOX_COMMIT: String = "628cb31ffa79cffffd34c2f9cde6cae044e4fc12"
    public const val GOMOBILE_VERSION: String = "v0.1.12"
    public const val FRAMEWORK_NAME: String = "Libbox.xcframework"

    /** Slices `build_libbox -target apple -platform ios,iossimulator` produces for the iOS targets we build. */
    public val expectedSlices: List<String> = listOf("ios-arm64", "ios-arm64_x86_64-simulator")

    /** SHA-256 of the retained xcframework zip; null until a real artifact exists (see docs/IOS_LIBBOX.md). */
    public val xcframeworkSha256: String? = null

    public val pinStatus: String get() = xcframeworkSha256 ?: "unpinned"

    public val isPinned: Boolean get() = xcframeworkSha256?.matches(Regex("^[0-9a-f]{64}$")) == true
}
