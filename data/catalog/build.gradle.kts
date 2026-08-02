plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.roam.data.catalog"
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
    implementation(libs.kotlinx.coroutines)
    implementation(libs.media3.datasource)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    // withTransaction: a bulk album edit re-parents every track at once, and
    // half an album pointing at a new id and half at the old one would split it.
    // room-runtime is declared explicitly rather than leant on arriving through
    // core:database's api() -- this module names RoamDatabase directly now.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(projects.core.datastore)
    // api, not implementation: ArtistPhotoEditor's public constructor takes a
    // Map<SourceType, Provider<SourceProvider>>, so any module that injects it
    // must be able to resolve SourceProvider or Kotlin reports "Cannot access
    // class ... Check your module classpath".
    api(projects.data.sourceApi)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
