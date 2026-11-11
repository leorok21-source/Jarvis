plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.leonid.jarvis"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.leonid.jarvis"
        minSdk = 33
        targetSdk = 35
        versionCode = 10
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}


dependencies {
    implementation(project(":llamaLib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
