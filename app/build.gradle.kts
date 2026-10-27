plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.streamhub.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.streamhub.tv"
        minSdk = 23
        targetSdk = 35
        val ciBuild = System.getenv("STREAMHUB_CI_BUILD_NUMBER")?.toIntOrNull()
        versionCode = if (ciBuild != null) 100000 + ciBuild else 22
        versionName = if (ciBuild != null) "3.1.$ciBuild" else "3.1.0"
    }

    signingConfigs {
        create("signedRelease") {
            storeFile = System.getenv("STREAMHUB_SIGNING_STORE_FILE")?.let { file(it) }
            storePassword = System.getenv("STREAMHUB_SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("STREAMHUB_SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("STREAMHUB_SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            if (!System.getenv("STREAMHUB_SIGNING_STORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("signedRelease")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("org.jsoup:jsoup:1.23.2")
    val media3Version = "1.8.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
