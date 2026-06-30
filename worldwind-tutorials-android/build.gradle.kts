import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

val targetSdkVersion = providers.gradleProperty("worldwind.targetSdk").get().toInt()
val minSdkVersion = providers.gradleProperty("worldwind.minSdk").get().toInt()

android {
    // Distinct namespace from the :worldwind-tutorials library; applicationId stays public.
    namespace = "${project.group}.tutorials.app"
    compileSdk = targetSdkVersion

    defaultConfig {
        applicationId = "${project.group}.tutorials"
        minSdk = minSdkVersion
        targetSdk = targetSdkVersion
        versionCode = providers.gradleProperty("worldwind.versionCode").get().toInt()
        versionName = version as String
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"))
        }
        // Release-grade build (debuggable=false → no JNI-trampoline / method-hook overhead, full JIT
        // inlining = real performance) but installable + profileable for simpleperf/Perfetto. Signed
        // with the debug key so it installs without a release keystore; minify off so trace symbols
        // stay readable. THIS is the build to profile, not `debug`. Build: installProfileable.
        create("profileable") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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
    implementation(project(":worldwind-tutorials"))
    coreLibraryDesugaring(libs.desugar)
}
