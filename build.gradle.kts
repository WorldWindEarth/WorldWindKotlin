plugins {
    val kotlinVersion = "1.8.10"
    kotlin("multiplatform") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
    kotlin("android") version kotlinVersion apply false
    id("org.jetbrains.dokka") version "1.8.10" apply false
    id("com.android.library") apply false
    id("com.android.application") apply false
}

buildscript {
    dependencies {
        classpath("dev.icerock.moko:resources-generator:0.21.1")
    }
}

allprojects {
    group = "earth.worldwind"
    version = "1.2.9"

    extra.apply {
        set("minSdk", 21)
        set("targetSdk", 33)
        set("versionCode", 5)
    }

    repositories {
        google()
        mavenCentral()
    }
}