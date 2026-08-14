plugins {
    alias(libs.plugins.agp)
    alias(libs.plugins.compose)
    alias(libs.plugins.serialization)
}

android {
    namespace = "com.mishuaipods"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mishuaipods"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("long", "BUILD_TIME", System.currentTimeMillis().toString())
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("SIGNING_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "mishuai"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val ks = System.getenv("SIGNING_KEYSTORE")
            if (ks != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo.includeInApk = false

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "META-INF/versions/**"
        }
    }
}

dependencies {
    implementation(libs.coreKtx)
    implementation(libs.libxposedApi)
    implementation(libs.libxposedService)
    implementation(libs.kotlinxSerializationJson)

    implementation(platform(libs.composeBom))
    implementation(libs.composeUi)
    implementation(libs.composeFoundation)
    implementation(libs.activityCompose)
    implementation(libs.material3)
}
