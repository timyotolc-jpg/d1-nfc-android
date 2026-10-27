plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// D1 Showcase — la demo web (sdk.argenticpay.io) empaquetada como app Android con el motor NFC adentro.
// El motor es el mismo código del módulo :wallet (se compila dentro de esta app): HCE, LUK, biometría, billetera predeterminada.
android {
    // namespace = paquete de la clase R: el motor (fuentes de :wallet) referencia R.string.* sin calificar, así que la R
    // tiene que ser io.argenticpay.d1wallet.R; el applicationId (lo que ve Play) sigue siendo io.argenticpay.d1showcase
    namespace = "io.argenticpay.d1wallet"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.argenticpay.d1showcase"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "../wallet/src/main/java")
        }
    }
    // misma upload key que el motor (wallet/release.jks la deja el workflow; secreto UPLOAD_KEY = "password:base64(jks)")
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
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
