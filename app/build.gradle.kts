plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.freedomfighter.readerspodcasts"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freedomfighter.readerspodcasts"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    flavorDimensions += "audience"
    productFlavors {
        // The private build is the one that will subscribe to YouTube channels (0.2): yt-dlp
        // and its Python come in as `priveImplementation`, so the public APK carries none of it.
        // Same split as Podcini and Podcini.X upstream, and for the same reasons.
        create("prive") {
            dimension = "audience"
            isDefault = true
            applicationIdSuffix = ".prive"
            buildConfigField("boolean", "YOUTUBE", "false")
        }
        create("publique") {
            dimension = "audience"
            buildConfigField("boolean", "YOUTUBE", "false")
        }
    }

    buildTypes { release { isMinifyEnabled = false } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // A pull parser on the JVM, so the feed reader can be tested without a device.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("xmlpull:xmlpull:1.1.3.1")
    // The real org.json, in place of the empty stub the unit-test android.jar carries.
    testImplementation("org.json:json:20231013")
}
