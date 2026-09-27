plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.cast.audio"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.cast.audio"
        // AudioPlaybackCaptureConfiguration (capture du son des autres applis) existe depuis Android 10.
        minSdk = 29
        targetSdk = 35
        // En CI, chaque build a un numéro croissant : Android accepte alors la mise à jour par-dessus.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        getByName("debug") {
            // Clé fixe versionnée : tous les APK publiés ont la même signature et s'installent
            // en mise à jour. Clé de développement uniquement, pas pour le Play Store.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signé avec la clé debug pour pouvoir installer l'APK directement.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // Le rapport est publié par la CI, mais ne bloque pas la génération de l'APK.
        abortOnError = false
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
}
