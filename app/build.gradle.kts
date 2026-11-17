plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.roombalocal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.roombalocal"
        minSdk = 26
        targetSdk = 34
        // En GitHub Actions cada compilación tiene un número mayor que la anterior
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(100)) ?: 3
        versionName = "1.2"
    }

    // Clave fija (en el repo) para que cada APK nuevo se instale encima del anterior
    // sin perder los ajustes. No es una clave secreta: no publiques la app en Play Store con ella.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("roomba.keystore")
            storePassword = "android"
            keyAlias = "roomba"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
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
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA"
            )
        }
    }
}

dependencies {
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bctls-jdk18on:1.78.1")
}
