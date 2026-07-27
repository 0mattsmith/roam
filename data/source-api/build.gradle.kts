plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.roam.data.source"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Most of media3-exoplayer / -datasource is annotated @UnstableApi, which
        // is @RequiresOptIn(level = ERROR) -- a hard compile error in Kotlin, not
        // a warning. Opting in per-file would mean an annotation on nearly every
        // class that touches the player.
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }
}

dependencies {
    api(projects.core.model)
    // Flow<RemoteFile> and DataSource.Factory appear in SourceProvider's public
    // signature, so these are `api` -- with `implementation` every consumer
    // module fails to resolve them.
    api(libs.kotlinx.coroutines)
    api(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.common)
}
