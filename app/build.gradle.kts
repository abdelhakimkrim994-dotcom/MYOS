plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val myosKeystorePath = System.getenv("MYOS_KEYSTORE_PATH")
val myosKeystorePassword = System.getenv("MYOS_KEYSTORE_PASSWORD")
val myosKeyAlias = System.getenv("MYOS_KEY_ALIAS")

android {
    namespace = "com.myos.home"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.myos.home"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.0.0"
    }

    signingConfigs {
        create("myosRelease") {
            storeFile = myosKeystorePath?.let { file(it) }
            storePassword = myosKeystorePassword
            keyAlias = myosKeyAlias
            keyPassword = myosKeystorePassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("myosRelease")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
