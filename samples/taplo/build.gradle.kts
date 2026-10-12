// Taplo companion: the DiPlay dashboard map as an app a launcher or cluster service embeds on the
// instrument cluster. DiPlay decodes into this app's view (TaploLinkService). It must be signed
// with the same key as DiPlay, because the link is a signature permission.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diplay.taplo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.diplay.taplo"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    // Same signing input as DiPlay's release build, so the signature permission is granted.
    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
