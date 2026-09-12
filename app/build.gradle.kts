plugins { id("com.android.application") }

android {
    namespace = "com.morningmission.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.morningmission.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
