plugins {
    val kotlinVersion = "2.1.20"
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
    version = "1.7.9"

    extra.apply {
        set("minSdk", 24)
        set("targetSdk", 35)
        set("versionCode", 15)
    }

    repositories {
        google()
        mavenCentral()
    }
}