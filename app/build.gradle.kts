plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pahtrik.warden"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pahtrik.warden"
        minSdk = 28
        targetSdk = 34
        versionCode = 19
        versionName = "2.8"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../warden-release.keystore")
            storePassword = System.getenv("WARDEN_KS_PASS") ?: "warden-local-only"
            keyAlias = "warden"
            keyPassword = System.getenv("WARDEN_KS_PASS") ?: "warden-local-only"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
