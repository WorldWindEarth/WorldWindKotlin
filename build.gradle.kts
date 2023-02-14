plugins {
    val kotlinVersion = "1.7.20"
    kotlin("multiplatform") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
    kotlin("android") version kotlinVersion apply false
    id("org.jetbrains.dokka") version kotlinVersion apply false
    id("com.android.library") apply false
    id("com.android.application") apply false
}

buildscript {
    dependencies {
        classpath("dev.icerock.moko:resources-generator:0.20.1")
    }
}

allprojects {
    group = "earth.worldwind"
    version = "1.1.27"

    extra.apply {
        set("minSdk", 26) // java.time requires Oreo. Use "isCoreLibraryDesugaringEnabled = true" to lower API to KitKat
        set("targetSdk", 33)
        set("versionCode", 3)
    }

    repositories {
        google()
        mavenCentral()
    }
}