import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 读取签名配置（keystore.properties 不入库，含密钥库路径与密码）
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("app/keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}
val keystoreFile = rootProject.file("app/${keystoreProperties["storeFile"]}")

android {
    namespace = "edu.campus.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "edu.campus.browser"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "0.8.0"
        // 仅保留真机常用 ABI，减小体积（x86/x86_64 仅模拟器需要）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.tencent:mmkv:1.3.9")

    // 二维码扫描（ZXing 官方 Android 封装，成熟高 star、离线可用）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // 启动前置拍照（CameraX：官方稳定、可指定前置、静默无 UI 拍照；v0.5.0 起）
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
}
