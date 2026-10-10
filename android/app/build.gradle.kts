import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Release signing stays outside the repository; see keystore.properties.example.
val releaseSigning = Properties().apply { rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use(::load) }
android {
    namespace = "app.monogatari.android"
    compileSdk = 36
    defaultConfig {
        // A separate installation for live-device checks keeps the user's reader and data untouched.
        applicationId = providers.gradleProperty("verificationApplicationId").getOrElse("app.monogatari.android")
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        releaseSigning.getProperty("storeFile")?.let { store ->
            create("release") {
                val password = releaseSigning.getProperty("storePassword") ?: rootProject.file(releaseSigning.getProperty("passwordFile")).readText().trim()
                storeFile = rootProject.file(store); storePassword = password
                keyAlias = releaseSigning.getProperty("keyAlias"); keyPassword = releaseSigning.getProperty("keyPassword") ?: password
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Phones and Chromebooks; the 32-bit x86 emulator ABI only inflates the package.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric nests app data under the temp directory; a short root keeps Windows paths below MAX_PATH.
        unitTests.all { it.systemProperty("java.io.tmpdir", layout.buildDirectory.dir("t").get().asFile.apply { mkdirs() }.absolutePath) }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.googlecode.plist:dd-plist:1.28")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.dylibso.chicory:runtime:1.5.1")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("androidx.webkit:webkit:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
