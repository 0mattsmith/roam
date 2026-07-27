import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Read signing config if present. Absent on a fresh clone -- debug builds still work.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.roam.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.roam.player"
        minSdk = 28
        targetSdk = 35

        // push.ps1 rewrites these two lines. Keep them on their own line,
        // formatted exactly like this, or the release script will not match them.
        versionCode = 1
        versionName = "0.1.0"

        // CI publishes a release on every green push to main and needs a
        // strictly increasing versionCode. It derives one from the commit count
        // and injects it here, so nothing has to commit back to the repo.
        // Locally these are absent and the literals above apply.
        System.getenv("ROAM_VERSION_CODE")?.toIntOrNull()?.let { versionCode = it }
        System.getenv("ROAM_VERSION_NAME")?.let { versionName = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // yt-dlp ships a Python runtime + FFmpeg per ABI. Splitting keeps each
    // APK around 45 MB instead of one 120 MB universal build.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true   // UpdateChecker needs BuildConfig.VERSION_CODE
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST"
        )
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    implementation(projects.data.sourceApi)
    implementation(projects.data.sourceDrive)
    implementation(projects.data.catalog)
    implementation(projects.feature.player)
    implementation(projects.feature.library)
    implementation(projects.feature.nowplaying)
    implementation(projects.feature.downloader)
    implementation(projects.feature.settings)
    implementation(projects.update)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.preview)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    implementation(libs.work.runtime)
}
