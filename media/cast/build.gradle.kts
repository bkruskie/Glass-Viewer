plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.universalmedia.media.cast"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":media:detection"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // Google Cast (free, uses the Default Media Receiver). A future "foss" flavor can drop these two.
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)

    testImplementation(libs.junit)
}
