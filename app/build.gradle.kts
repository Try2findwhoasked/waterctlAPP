plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.waterctl.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.waterctl.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("waterctl-release.keystore")
            storePassword = "waterctl123"
            keyAlias = "waterctl"
            keyPassword = "waterctl123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    // pure Android framework + Kotlin stdlib; no external libraries
    implementation(kotlin("stdlib"))
}