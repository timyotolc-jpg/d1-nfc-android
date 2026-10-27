plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.argenticpay.d1pos"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.argenticpay.d1pos"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "3.1"
    }
    // Firma estable (misma upload key que el motor): así cada versión se instala ENCIMA de la anterior y la configuración
    // (sesión, impresora, moneda) no se pierde. Antes el APK iba firmado con una debug key nueva en cada build de CI y
    // Android obligaba a desinstalar.
    val ksFile = file("../wallet/release.jks")
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
