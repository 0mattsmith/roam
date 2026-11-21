plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/**
 * Getting music you do not have yet: finding it, matching it exactly, tagging
 * it and putting it on Drive. Everything here runs without a screen.
 *
 * A data module rather than part of a feature, because more than one screen
 * needs it and a feature may not depend on another feature -- which is what
 * kept the library's search page from offering a + at all (docs/SEARCH.md,
 * "Where it lives"). A worker in a data module is not new: SyncWorker lives in
 * :data:catalog.
 *
 * The classes keep the "Download" names they were born with until offline
 * pinning lands (CLAUDE.md's naming note); the module is new, so it has the
 * right name from the start.
 */
android {
    namespace = "app.roam.data.acquire"
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
    implementation(projects.core.model)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    // api: AlbumPlacement.from and the identity lookups carry catalogue
    // types (ReleaseMatch, RecordingMatch) in their public signatures.
    api(projects.data.catalog)
    api(projects.data.sourceApi)
    // yt-dlp itself, plus the FFmpeg build it shells out to. FFmpegKit was
    // retired in April 2025, so this bundle is the maintained way to get both
    // binaries onto a device without shipping them yourself.
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    // api: DownloadStatus exposes WorkInfo.State, and the screens observing
    // the Adding list read WorkManager directly.
    api(libs.work.runtime)
    // setProgressAsync returns a ListenableFuture, and WorkManager does not put
    // that class on the compile classpath. The worker used to get it by
    // accident, through :feature:player's media3; this module has no media3.
    implementation(libs.guava.listenablefuture)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // The matching, naming and parsing here is pure, so it is provable on the JVM.
    testImplementation(libs.junit)
    // org.json ships inside android.jar as stubs that throw "Stub!", so the
    // SourceResults tests would die on their first JSONObject without it.
    testImplementation(libs.org.json)
}
