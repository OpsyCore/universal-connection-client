plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
}
