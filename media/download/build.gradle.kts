plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.universalmedia.media.download"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:database"))
    api(project(":core:datastore"))
    api(project(":media:detection"))
    api(libs.okhttp)
    api(libs.androidx.work.runtime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.common)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
