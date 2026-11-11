plugins { id("com.android.application") }

android {
    namespace = "org.local.rbe902vc"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.local.rbe902vc"
        minSdk = 34
        targetSdk = 34
        versionCode = 5
        versionName = "0.5-scan-file"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
