plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "kz.arkotlinreserve.tracking.opencv"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":ar-core"))
    implementation(libs.opencv)
    testImplementation(libs.junit)
}
