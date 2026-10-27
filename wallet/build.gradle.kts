plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.argenticpay.d1wallet"
    compileSdk = 36   // Google Play exige target 36 (Android 16) para apps nuevas, prueba interna incluida

    defaultConfig {
        applicationId = "io.argenticpay.d1wallet"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.3"
    }
    // Firma de release: el workflow deja la upload key en wallet/release.jks (secreto UPLOAD_KEY = "password:base64(jks)")
    val ksFile = file("release.jks")
    val ksPass = System.getenv("KEYSTORE_PASSWORD") ?: ""
    signingConfigs {
        create("release") {
            if (ksFile.exists() && ksPass.isNotEmpty()) {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = "d1wallet"
                keyPassword = ksPass
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile.exists() && ksPass.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
