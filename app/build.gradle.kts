import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.procrastinationgate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.procrastinationgate"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

val releaseSigningFile = rootProject.file("keystore.properties")
if (releaseSigningFile.isFile) {
    val releaseSigning = Properties().apply {
        releaseSigningFile.inputStream().use(::load)
    }
    android.signingConfigs.create("release") {
        storeFile = rootProject.file(requireNotNull(releaseSigning.getProperty("storeFile")))
        storePassword = requireNotNull(releaseSigning.getProperty("storePassword"))
        keyAlias = requireNotNull(releaseSigning.getProperty("keyAlias"))
        keyPassword = requireNotNull(releaseSigning.getProperty("keyPassword"))
    }
    android.buildTypes.getByName("release") {
        signingConfig = android.signingConfigs.getByName("release")
    }
}
