plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// No Compose. The only UI this module has is HTML served out of assets; the
// switch that starts it lives in :feature:settings like every other setting.
android {
    namespace = "app.roam.feature.webui"
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
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    // For ArtworkStore only -- the browser needs the cover bytes, and they are
    // already on disk. Deliberately NOT :feature:library: this is a second
    // front end over the same catalogue, not a wrapper round the first one.
    implementation(projects.data.catalog)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.nanohttpd)
    implementation(libs.androidx.core.ktx)
    // LifecycleService, so the settings collector is scoped to the service
    // rather than to a scope somebody has to remember to cancel.
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
