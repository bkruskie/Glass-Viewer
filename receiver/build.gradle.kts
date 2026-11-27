// Universal Media Receiver: a small separate app for Android TV / Google TV / Fire TV
// (also runs on tablets) that shows a phone's mirrored screen.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.universalmedia.receiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.universalmedia.receiver"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("testing") {
            storeFile = file("../app/testing.keystore")
            storePassword = "universalmedia"
            keyAlias = "testing"
            keyPassword = "universalmedia"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("testing") }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":media:mirror-protocol"))
}
