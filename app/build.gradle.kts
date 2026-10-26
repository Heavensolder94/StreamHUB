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
        versionCode = 7
        versionName = "1.6.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
