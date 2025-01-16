plugins {
    kotlin("android")
    id("com.android.application")
}

android {
    namespace = "${project.group}.examples"
    compileSdk = extra["targetSdk"] as Int

    defaultConfig {
        applicationId = namespace
        minSdk = extra["minSdk"] as Int
        targetSdk = extra["targetSdk"] as Int
        versionCode = extra["versionCode"] as Int
        versionName = version as String
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"))
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = extra["javaVersion"] as JavaVersion
        targetCompatibility = extra["javaVersion"] as JavaVersion
    }

    kotlinOptions {
        jvmTarget = extra["javaVersion"].toString()
    }
}

dependencies {
    implementation(project(":worldwind"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.github.missioncommand:mil-sym-android-renderer:0.1.60")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}