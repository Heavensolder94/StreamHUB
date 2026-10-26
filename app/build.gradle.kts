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
        versionCode = 11
        versionName = "2.0.0"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jsoup:jsoup:1.23.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
