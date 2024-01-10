plugins {
    val kotlinVersion = "1.8.22"
    kotlin("multiplatform") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
    kotlin("android") version kotlinVersion apply false
    id("org.jetbrains.dokka") version "1.8.20" apply false
    id("com.android.library") apply false
    id("com.android.application") apply false
}

buildscript {
    dependencies {
        classpath("dev.icerock.moko:resources-generator:0.23.0")
    }
}

allprojects {
    group = "earth.worldwind"
    version = "1.4.1"

    extra.apply {
        set("minSdk", 21)
        set("targetSdk", 34)
        set("versionCode", 9)
        set("javaVersion", JavaVersion.VERSION_1_8)
    }

    repositories {
        google()
        mavenCentral()
    }
}