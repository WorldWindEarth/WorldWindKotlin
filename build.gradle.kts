plugins {
    val kotlinVersion = "2.1.20"
    kotlin("multiplatform") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
    kotlin("android") version kotlinVersion apply false
    id("com.android.library") apply false
    id("com.android.application") apply false
    id("org.jetbrains.dokka") version "2.0.0" apply false
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

buildscript {
    dependencies {
        classpath(libs.moko.resources.generator)
    }
}

allprojects {
    group = "earth.worldwind"
    version = "1.8.2"

    extra.apply {
        set("minSdk", 24)
        set("targetSdk", 35)
        set("versionCode", 16)
    }

    repositories {
        google()
        mavenCentral()
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("SONATYPE_USERNAME"))
            password.set(System.getenv("SONATYPE_PASSWORD"))
        }
    }
}