import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 018 開源：-Ppublic 建公開版 APK（不帶開發者的預設伺服器與釘選、用 release.properties 的正式簽章）；
// 平常的建置讀 ../server.properties（開發者自己的伺服器，不進公開版）。兩個檔都可以不存在。
val isPublic = project.hasProperty("public")
fun props(name: String) = Properties().apply { rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use { load(it) } }
val server = if (isPublic) Properties() else props("server.properties")
val releaseKey = if (isPublic) props("release.properties") else Properties()

android {
    namespace = "com.anybacking.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"   // 映像內建這版（不指定會每次下載 34）

    defaultConfig {
        applicationId = "com.anybacking.app"   // 上架後不能改；P3 上架前再確認一次
        minSdk = 33                            // 動態背景用 AGSL（Android 13）
        targetSdk = 35
        versionCode = 10000      // 主＊10000＋次＊100＋修訂
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "DEFAULT_SERVER", "\"${server.getProperty("server", "")}\"")
        buildConfigField("String", "PINS", "\"${server.getProperty("pins", "")}\"")
    }

    signingConfigs {
        // 固定 debug 簽章（非機密；password=android），覆蓋安裝免移除；沒有這個檔（公開版）就用 Android 預設的 debug 簽章
        if (file("debug.keystore").exists()) getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // 公開 APK 的正式簽章：release.properties（storeFile、storePassword、keyAlias、keyPassword），不進任何 repo
        if (releaseKey.isNotEmpty()) create("release") {
            storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            // 開發者自己的建置：沿用固定的 debug 簽章，才能直接覆蓋安裝 debug 版（保留裝置金鑰與播放紀錄）。
            // 公開版（-Ppublic）用 release.properties 的正式簽章。
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true      // R8：精簡、混淆、最佳化
            isShrinkResources = true    // 拿掉用不到的資源
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // 授權：全部 Apache 2.0（憲章原則 I：不得打包 GPL）
    implementation("androidx.core:core-ktx:1.15.0")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")   // 背景播放（016）

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
