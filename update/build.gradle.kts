plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.roam.update"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(projects.core.common)
    implementation(projects.core.datastore)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
