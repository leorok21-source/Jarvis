plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.leonid.jarvis"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.leonid.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}


dependencies {
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
