plugins {
    val kotlinVersion = "2.1.10"
    kotlin("multiplatform") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
    kotlin("android") version kotlinVersion apply false
    id("org.jetbrains.dokka") version "2.0.0" apply false
    id("com.android.library") apply false
    id("com.android.application") apply false
}

buildscript {
    dependencies {
        classpath(libs.moko.resources.generator)
    }
}

allprojects {
    group = "earth.worldwind"
    version = "1.7.2"

    extra.apply {
        set("minSdk", 21)
        set("targetSdk", 34)
        set("versionCode", 14)
    }

    repositories {
        google()
        mavenCentral()
    }
}