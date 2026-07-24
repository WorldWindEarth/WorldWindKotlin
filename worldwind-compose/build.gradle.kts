import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("maven-publish")
    id("signing")
    id("org.jetbrains.dokka")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    js {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
    }
    // iosX64 (Intel-Mac simulator) intentionally dropped — Compose Multiplatform stopped
    // publishing the iosX64 variant. Core :worldwind still targets iosX64 for non-Compose
    // consumers; only the Compose binding requires Apple-Silicon simulators / devices.
    iosArm64()
    iosSimulatorArm64()
    android {
        namespace = "earth.worldwind.compose"
        compileSdk = providers.gradleProperty("worldwind.targetSdk").get().toInt()
        minSdk = providers.gradleProperty("worldwind.minSdk").get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                api(project(":worldwind"))
                implementation(libs.compose.runtime)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.androidx.lifecycle.runtime.compose)
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
            }
        }
        jsMain {
            dependencies {
                implementation(libs.compose.html.core)
            }
        }
        // Skia-canvas Compose/Web: cannot embed WebGL in its surface, so the wasmJs WorldWindow
        // binding hosts the globe on a separate DOM canvas behind a transparent Compose surface.
        wasmJsMain {
            dependencies {
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.kotlinx.browser)
            }
        }
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain.get())
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                // Compose UI brings the iOS UIKitView interop API.
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
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

val dokkaOutputDir = layout.buildDirectory.dir("dokka")
val deleteDokkaOutputDir by tasks.register<Delete>("deleteDokkaOutputDirectory") {
    delete(dokkaOutputDir)
}
val javadocJar = tasks.register<Jar>("javadocJar") {
    dependsOn(deleteDokkaOutputDir, tasks.dokkaGeneratePublicationHtml)
    archiveClassifier.set("javadoc")
    from(dokkaOutputDir)
}
// Empty javadoc jar for per-target publications; satisfies Maven Central without duplicating Dokka output
val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar") {
    archiveClassifier.set("javadoc")
    archiveAppendix.set("empty")
}

dokka {
    moduleName.set("WorldWind Kotlin Compose")
    pluginsConfiguration.html { footerMessage.set("(c) WorldWind Earth") }
    dokkaPublications.html { outputDirectory.set(dokkaOutputDir) }
}

publishing {
    publications {
        withType<MavenPublication> {
            // Full Dokka docs ship once on the root publication; targets carry an empty javadoc jar
            artifact(if (name == "kotlinMultiplatform") javadocJar else emptyJavadocJar)
            pom {
                name.set("WorldWind Kotlin Compose")
                description.set("Compose Multiplatform bindings for the WorldWind Kotlin SDK — exposes the engine as a unified @Composable WorldWindow on Android, Desktop (JVM/Swing), and Web (Compose HTML).")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                url.set("https://worldwind.earth")
                issueManagement {
                    system.set("Github")
                    url.set("https://github.com/WorldWindEarth/WorldWindKotlin/issues")
                }
                scm {
                    connection.set("https://github.com/WorldWindEarth/WorldWindKotlin.git")
                    url.set("https://github.com/WorldWindEarth/WorldWindKotlin")
                }
                developers {
                    developer {
                        name.set("Eugene Maksymenko")
                        email.set("support@worldwind.earth")
                    }
                }
            }
        }
    }
}

signing {
    useInMemoryPgpKeys(
        System.getenv("GPG_PRIVATE_KEY"),
        System.getenv("GPG_PRIVATE_PASSWORD")
    )
    if (!System.getenv("GPG_PRIVATE_KEY").isNullOrBlank()) sign(publishing.publications)
}

// https://github.com/gradle/gradle/issues/26091
tasks.withType<AbstractPublishToMaven>().configureEach {
    val signingTasks = tasks.withType<Sign>()
    mustRunAfter(signingTasks)
}
