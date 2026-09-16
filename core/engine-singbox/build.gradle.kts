import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.ucc.core.singbox.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

/**
 * libbox.aar is built from the pinned sing-box tag by CI (.github/workflows/libbox.yml)
 * and dropped into core/engine-singbox/libs/. It is not committed (see .gitignore).
 * The expected SHA-256 lives in libbox.sha256; a mismatch fails the build so a
 * tampered or stale core can never be linked silently.
 */
val libboxAar = layout.projectDirectory.file("libs/libbox.aar")
val libboxChecksum = layout.projectDirectory.file("libbox.sha256")

val verifyLibbox by tasks.registering {
    inputs.file(libboxAar).optional()
    inputs.file(libboxChecksum)
    doLast {
        val aar = libboxAar.asFile
        require(aar.exists()) {
            "core/engine-singbox/libs/libbox.aar is missing. Build it with the 'libbox' CI workflow " +
                "or `tools/build-libbox.sh`, see docs/CORE_DECISION.md §5."
        }
        val expected = libboxChecksum.asFile.readText().trim().substringBefore(' ').lowercase()
        if (expected.isEmpty() || expected == "unpinned") {
            logger.warn("libbox.sha256 is unpinned — checksum verification skipped (development only)")
            return@doLast
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
        require(actual == expected) { "libbox.aar checksum mismatch: expected $expected, got $actual" }
    }
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("merge") }.configureEach {
    dependsOn(verifyLibbox)
}

dependencies {
    api(project(":core:engine-api"))
    api(project(":core:singbox-config"))
    implementation(files("libs/libbox.aar"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
}
