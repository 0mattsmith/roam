plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.roam.feature.nowplaying"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.lifecycle.compose)
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.data.catalog)
    implementation(projects.feature.player)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.hilt.navigation)
    implementation(libs.coil.compose)
    implementation(libs.paging.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
