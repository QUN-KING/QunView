plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace="com.qunking.qunview"
    compileSdk=35
    defaultConfig { applicationId="com.qunking.qunview"; minSdk=26; targetSdk=35; versionCode=1; versionName="0.1.0" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("io.github.webrtc-sdk:android:125.6422")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}