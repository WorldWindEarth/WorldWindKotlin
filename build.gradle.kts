plugins {
    val kotlinVersion = "1.9.23"
    kotlin("multiplatform") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
    kotlin("android") version kotlinVersion apply false
    id("org.jetbrains.dokka") version "1.9.10" apply false
    id("com.android.library") apply false
    id("com.android.application") apply false
}

buildscript {
    dependencies {
        classpath("dev.icerock.moko:resources-generator:0.24.0-beta-2")
    }
}

allprojects {
    group = "earth.worldwind"
    version = "1.5.17"

    extra.apply {
        set("minSdk", 21)
        set("targetSdk", 34)
        set("versionCode", 11)
        set("javaVersion", JavaVersion.VERSION_17)
    }

    repositories {
        google()
        mavenCentral()
    }
}