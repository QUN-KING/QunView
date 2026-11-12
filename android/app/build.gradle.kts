val releaseKeystorePath = System.getenv("QUNVIEW_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("QUNVIEW_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("QUNVIEW_KEY_ALIAS")
val releaseKeyPassword = System.getenv("QUNVIEW_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace="com.qunking.qunview"
    compileSdk=35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    defaultConfig { applicationId="com.qunking.qunview"; minSdk=26; targetSdk=35; versionCode=1; versionName="0.1.0" }
    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}