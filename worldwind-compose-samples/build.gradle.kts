import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("dev.icerock.mobile.multiplatform-resources")
    id("org.jetbrains.compose")
}

multiplatformResources {
    resourcesPackage.set("${project.group}.compose.samples")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
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
    @Suppress("UnstableApiUsage")
    android {
        namespace = "earth.worldwind.compose.samples"
        compileSdk = providers.gradleProperty("worldwind.targetSdk").get().toInt()
        minSdk = providers.gradleProperty("worldwind.minSdk").get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        androidResources {
            enable = true
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":worldwind-compose"))
            }
        }
        androidMain {
            dependencies {
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(libs.androidx.activity.compose)
            }
        }
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        jsMain {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.html.core)
            }
        }
        all {
            languageSettings {
                @Suppress("OPT_IN_USAGE")
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
}

tasks.register<JavaExec>("runJvmComposeSample") {
    group = "application"
    description = "Run the Compose Desktop sample app."
    mainClass.set("earth.worldwind.compose.samples.MainKt")
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = mainCompilation.runtimeDependencyFiles + mainCompilation.output.allOutputs
    dependsOn(mainCompilation.compileTaskProvider)
}
