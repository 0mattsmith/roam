plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.roam.data.source.drive"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    api(projects.data.sourceApi)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.auth)
    implementation(libs.security.crypto)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
