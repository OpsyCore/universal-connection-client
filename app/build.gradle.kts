import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing (docs/RELEASE.md §Signing). Nothing is committed: the keystore
 * and its passwords come from either
 *   1. environment variables  UCC_KEYSTORE_FILE / UCC_KEYSTORE_PASSWORD / UCC_KEY_ALIAS / UCC_KEY_PASSWORD  (CI), or
 *   2. an untracked  keystore.properties  next to this file with the keys
 *      storeFile / storePassword / keyAlias / keyPassword                          (local).
 * When neither is present the release build is produced UNSIGNED (file name gets
 * "-unsigned") so CI can still verify minification without any secret.
 */
val releaseSigning: Map<String, String>? = run {
    val env = System.getenv()
    val fromEnv = listOf("UCC_KEYSTORE_FILE", "UCC_KEYSTORE_PASSWORD", "UCC_KEY_ALIAS", "UCC_KEY_PASSWORD").map { env[it] }
    if (fromEnv.all { !it.isNullOrBlank() }) {
        return@run mapOf("storeFile" to fromEnv[0]!!, "storePassword" to fromEnv[1]!!, "keyAlias" to fromEnv[2]!!, "keyPassword" to fromEnv[3]!!)
    }
    val local = rootProject.file("app/keystore.properties")
    if (local.exists()) {
        val p = Properties().apply { local.inputStream().use { load(it) } }
        val m = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { p.getProperty(it).orEmpty() }
        if (m.values.all { it.isNotBlank() }) return@run m
    }
    null
}

/** VIP card URL: env UCC_VIP_URL → gradle property `vipUrl` → "" (hidden). Validated so a typo cannot ship a dead button. */
fun vipUrl(): String {
    val raw = (System.getenv("UCC_VIP_URL") ?: (project.findProperty("vipUrl") as String?) ?: "").trim()
    if (raw.isEmpty()) return ""
    require(raw.startsWith("https://") || raw.startsWith("http://") || raw.startsWith("tg://")) { "vipUrl must be http(s):// or tg://" }
    require(!raw.contains("your_vip_bot") && !raw.contains('"') && !raw.contains('\\')) { "vipUrl looks like a placeholder or contains illegal characters" }
    return raw
}

android {
    namespace = "io.ucc.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.ucc.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Release versioning: semantic versionName, monotonically increasing versionCode (bump both per release).
        versionCode = 4
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "fa")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Core selection. One flavour per engine; the flavour decides which engine
    // module is compiled in (see dependencies{} below) and which CORE_ID
    // CoreFactories.selected() resolves. Only the sing-box flavour exists today.
    flavorDimensions += "core"
    productFlavors {
        create("singbox") {
            dimension = "core"
            buildConfigField("String", "CORE_ID", "\"singbox\"")
            buildConfigField("String", "SOURCE_URL", "\"https://github.com/OpsyCore/universal-connection-client\"")
            // VIP / affiliate card on Home (v1.0.3). Empty (default) = card hidden. Set UCC_VIP_URL (env, CI variable)
            // or vipUrl in gradle.properties / local.properties; must be http(s):// or tg://. No placeholder URL is ever shown.
            buildConfigField("String", "VIP_URL", "\"${vipUrl()}\"")
        }
    }

    signingConfigs {
        releaseSigning?.let { cfg ->
            create("release") {
                storeFile = file(cfg.getValue("storeFile"))
                storePassword = cfg.getValue("storePassword")
                keyAlias = cfg.getValue("keyAlias")
                keyPassword = cfg.getValue("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning?.let { signingConfigs.getByName("release") }
        }
    }
    if (releaseSigning == null) {
        logger.lifecycle("ucc: no release signing material found — release artifacts will be UNSIGNED")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // DebugProbesKt.bin is kotlinx-coroutines' debug-agent stub; unused by the app, so keep it out of the artifact.
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "DebugProbesKt.bin")
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:engine-api"))
    implementation(project(":core:vpn"))
    implementation(project(":core:config"))
    implementation(project(":core:smart"))
    implementation(project(":core:app-logic"))
    // Engine modules are flavour-scoped: nothing in src/main may import them except io.ucc.app.core.CoreFactories.
    "singboxImplementation"(project(":core:engine-singbox"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // QR scanning: CameraX (Apache-2.0) + ML Kit barcode (bundled model; Google ML Kit terms — see docs/CONFIG_FORMATS.md §QR)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Google Mobile Ads: DEBUG ONLY (app/src/debug — test app/unit IDs for placement preview). The release APK/AAB
    // contains no advertising SDK; see app/src/release/.../Ads.kt (no-op) and docs/PRIVACY_POLICY.md.
    debugImplementation(libs.play.services.ads)
    debugImplementation(libs.guava) // resolves the listenablefuture:9999.0-empty conflict between play-services and CameraX
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
