@file:Suppress("UnstableApiUsage")

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
        getByName("release") {
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
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("io.github.missioncommand:mil-sym-android-renderer:0.1.60")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}