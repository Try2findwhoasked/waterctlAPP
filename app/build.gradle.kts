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
        versionName = "1.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 仓库内的发布密钥仅用于本项目的官方 APK。fork 或 CI 环境里没有这个文件时，
    // 回退到 debug 签名，保证 `assembleRelease` 仍然可用。
    // 也可以通过环境变量覆盖（CI 上更安全的做法）：
    //   WATERCTL_KEYSTORE / WATERCTL_STORE_PASSWORD / WATERCTL_KEY_ALIAS / WATERCTL_KEY_PASSWORD
    val keystorePath = System.getenv("WATERCTL_KEYSTORE")
        ?.let { file(it) }
        ?: rootProject.file("waterctl-release.keystore")
    val hasReleaseKeystore = keystorePath.exists()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = keystorePath
                storePassword = System.getenv("WATERCTL_STORE_PASSWORD") ?: "waterctl123"
                keyAlias = System.getenv("WATERCTL_KEY_ALIAS") ?: "waterctl"
                keyPassword = System.getenv("WATERCTL_KEY_PASSWORD") ?: "waterctl123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // 没有发布密钥：用 debug 签名，仅适用于自测 / fork
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    // pure Android framework + Kotlin stdlib; no external libraries
    implementation(kotlin("stdlib"))
}