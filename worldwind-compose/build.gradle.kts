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
    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    @Suppress("UnstableApiUsage")
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
                implementation(compose.runtime)
            }
        }
        androidMain {
            dependencies {
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(libs.androidx.lifecycle.runtime.compose)
            }
        }
        jvmMain {
            dependencies {
                implementation(compose.foundation)
                implementation(compose.ui)
            }
        }
        jsMain {
            dependencies {
                implementation(compose.html.core)
            }
        }
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain.get())
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                // Compose UI brings the iOS UIKitView interop API.
                implementation(compose.foundation)
                implementation(compose.ui)
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

dokka {
    moduleName.set("WorldWind Kotlin Compose")
    pluginsConfiguration.html { footerMessage.set("(c) WorldWind Earth") }
    dokkaPublications.html { outputDirectory.set(dokkaOutputDir) }
}

publishing {
    publications {
        withType<MavenPublication> {
            artifact(javadocJar)
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
