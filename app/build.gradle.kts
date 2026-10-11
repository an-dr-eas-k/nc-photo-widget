plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.an_dr_eas_k.ncphotowidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.an_dr_eas_k.ncphotowidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Unset outside the maintainer's machine, so F-Droid builds an unsigned APK.
    val releaseKeystore = System.getenv("NCPW_KEYSTORE")
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("NCPW_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("NCPW_KEY_ALIAS") ?: "ncphotowidget"
            keyPassword = System.getenv("NCPW_KEY_PASSWORD") ?: System.getenv("NCPW_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Google's signed dependency metadata blob is not reproducible and is rejected by F-Droid.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
