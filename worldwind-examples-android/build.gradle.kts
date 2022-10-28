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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(project(":worldwind"))
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("com.google.android.material:material:1.7.0")
    implementation("io.github.missioncommand:mil-sym-android-renderer:0.1.50")
}