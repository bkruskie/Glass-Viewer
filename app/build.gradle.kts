plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.universalmedia"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.universalmedia"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.15.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Fixed key for test builds so each new APK installs over the previous one.
        // Not secret; release builds will use a private key from CI secrets (Milestone 8).
        create("testing") {
            storeFile = file("testing.keystore")
            storePassword = "universalmedia"
            keyAlias = "testing"
            keyPassword = "universalmedia"
        }
    }

    buildTypes {
        release {
            // Enabled and verified during Milestone 8 (release preparation).
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("testing")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":media:detection"))
    implementation(project(":media:download"))
    implementation(project(":media:playback"))
    implementation(project(":media:cast"))
    implementation(project(":media:mirror"))
    implementation(project(":media:transfer"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:player"))
    implementation(project(":feature:library"))
    implementation(project(":feature:connect"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
