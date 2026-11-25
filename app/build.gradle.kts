plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.okww.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.okww.mobile"
        minSdk = 26
        // 可通过 -PtargetSdk=32 覆盖：部分 ROM（realme / ColorOS 等）对 targetSdk >= 33
        // 的侧载应用强制「受限设置」，导致无障碍无法开启；targetSdk 32 可绕过该限制。
        targetSdk = (project.findProperty("targetSdk") as String?)?.toIntOrNull() ?: 35
        versionCode = 4
        versionName = "0.1.3"
        resourceConfigurations.addAll(listOf("zh", "en"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
