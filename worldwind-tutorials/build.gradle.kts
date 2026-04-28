import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.application")
    id("dev.icerock.mobile.multiplatform-resources")
}

multiplatformResources {
    resourcesPackage.set("${project.group}.tutorials")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        testRuns["test"].executionTask.configure {
            useJUnit()
        }
    }
    js(IR) {
        binaries.executable()
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":worldwind"))
            }
        }
        androidMain {
            dependencies {
                implementation(libs.androidx.appcompat)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.material)
            }
        }
        jvmMain {
            dependencies {
                // VLCJ — uses the host's installed VLC 3.0+ libraries (no bundled natives).
                implementation(libs.vlcj)
                // JavaCV / FFmpeg via javacpp-presets. Pulls FFmpeg native binaries for the
                // current platform automatically. Used by both JavaCv* (FFmpegFrameGrabber)
                // and FFmpeg* (raw avformat/avcodec/swscale) tutorials.
                implementation(libs.javacv.platform)
                // JavaFX Media — bundles its own gstreamer-based decoder. Used by
                // JavaFxVideoTexture. The runtime JARs are platform-classified, and the
                // version-catalog provider syntax doesn't compose with `{ artifact { ... }}`,
                // so we expand the GAV to a string and append the host classifier.
                val javafxVersion = libs.versions.javafx.get()
                val javafxPlatform = when {
                    System.getProperty("os.name").lowercase().contains("win") -> "win"
                    System.getProperty("os.name").lowercase().contains("mac") -> "mac"
                    else -> "linux"
                }
                implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-media:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxPlatform")
            }
        }
        all {
            languageSettings {
                @Suppress("OPT_IN_USAGE")
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                    optIn.add("kotlin.time.ExperimentalTime")
                }
            }
        }
    }
}

android {
    namespace = "${project.group}.tutorials"
    compileSdk = extra["targetSdk"] as Int
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")

    defaultConfig {
        applicationId = namespace
        minSdk = extra["minSdk"] as Int
        targetSdk = extra["targetSdk"] as Int
        versionCode = extra["versionCode"] as Int
        versionName = version as String
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"))
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
}

// ---------------------------------------------------------------------------
// JVM-tutorials run task with JavaFX on the module path.
//
// Since JDK 9, JavaFX rejects its own classes when they're loaded from the unnamed module
// (i.e. plain classpath) — `Platform.startup(Runnable)` throws:
//   "Unsupported JavaFX configuration: classes were loaded from 'unnamed module @...'"
// The fix is to load `javafx-*.jar` from the *module path* via `--module-path` and pull
// the modules in with `--add-modules`. We can't apply the `application` plugin here (it
// conflicts with `com.android.application`), and the `org.openjfx.javafxplugin` Gradle
// plugin requires it — so we wire a plain `JavaExec` task that does the same thing.
//
// Run from the CLI: `./gradlew :worldwind-tutorials:runJvmTutorials`
// To run from IntelliJ's green Play button on `BasicGlobeTutorial.main`, copy the
// `--module-path ... --add-modules ...` args this task computes into the run config's
// "VM options" field.
// ---------------------------------------------------------------------------
tasks.register<JavaExec>("runJvmTutorials") {
    group = "application"
    description = "Run the JVM Swing tutorials with JavaFX configured on the module path."
    mainClass.set("earth.worldwind.tutorials.BasicGlobeTutorialKt")
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = mainCompilation.runtimeDependencyFiles + mainCompilation.output.allOutputs
    dependsOn(mainCompilation.compileTaskProvider)

    doFirst {
        // Pull JavaFX JARs out of the resolved runtime classpath and route them onto the
        // module path. Anything else (Kotlin stdlib, VLCJ, JavaCV, …) stays on the
        // classpath where it belongs. `File.pathSeparator` is `;` on Windows, `:` else.
        val javafxJars = classpath.files.filter {
            it.name.startsWith("javafx-") && it.name.endsWith(".jar")
        }
        if (javafxJars.isNotEmpty()) {
            jvmArgs("--module-path", javafxJars.joinToString(File.pathSeparator))
            jvmArgs(
                "--add-modules",
                "javafx.base,javafx.graphics,javafx.media,javafx.swing,javafx.controls"
            )
        }
    }
}