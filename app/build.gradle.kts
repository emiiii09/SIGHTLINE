plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// No secrets here by design: every user types their own Meta API key
// in Settings, kept in memory only for the session. Nothing to configure.

android {
    namespace = "com.sightline.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sightline.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.7.0-hackathon"
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
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("junit:junit:4.13.2")
}
