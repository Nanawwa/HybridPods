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
