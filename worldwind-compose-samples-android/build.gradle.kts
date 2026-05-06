import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

val targetSdkVersion = providers.gradleProperty("worldwind.targetSdk").get().toInt()
val minSdkVersion = providers.gradleProperty("worldwind.minSdk").get().toInt()

android {
    namespace = "${project.group}.compose.samples.app"
    compileSdk = targetSdkVersion

    defaultConfig {
        applicationId = "${project.group}.compose.samples"
        minSdk = minSdkVersion
        targetSdk = targetSdkVersion
        versionCode = providers.gradleProperty("worldwind.versionCode").get().toInt()
        versionName = version as String
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":worldwind-compose-samples"))
    coreLibraryDesugaring(libs.desugar)
}
