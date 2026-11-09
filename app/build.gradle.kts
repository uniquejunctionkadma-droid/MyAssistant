plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.tanya.assistant"
    compileSdk = 34
    signingConfigs {
        create("fixed") {
            storeFile = file("fixed.keystore")
            storePassword = "android"
            keyAlias = "key"
            keyPassword = "android"
        }
    }
    defaultConfig {
        applicationId = "com.tanya.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
