import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("dev.icerock.mobile.multiplatform-resources")
    id("maven-publish")
    id("signing")
    id("org.jetbrains.dokka")
}

multiplatformResources {
    resourcesPackage.set(project.group.toString())
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
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
    }
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.xml)
                implementation(libs.ktor.client.core)
                implementation(libs.uri.kmp)
                implementation(libs.pngj)
                implementation(libs.tiff)
                api(libs.moko.resources)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.moko.resources.test)
            }
        }
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.okhttp)
                compileOnly(libs.ormlite.core)
            }
        }
        val jvmCommonTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.mockk.jvm)
            }
        }
        jvmMain {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.gluegen)
                implementation(libs.jogl)

                implementation(libs.lwjgl)
                implementation(libs.lwjgl.assimp)
                implementation(libs.lwjgl.glfw)
                implementation(libs.lwjgl.openal)
                implementation(libs.lwjgl.opengl)
                implementation(libs.lwjgl.stb)

                implementation(libs.mil.sym.jvm)
                implementation(libs.ormlite.jdbc)
            }
        }
        jvmTest {
            dependsOn(jvmCommonTest)
        }
        jsMain {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        jsTest {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
        androidMain {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.androidx.annotation)
                implementation(libs.androidx.appcompat.resources)
                implementation(libs.mil.sym.android)
                implementation(libs.ormlite.android)
            }
        }
        androidUnitTest {
            dependsOn(jvmCommonTest)
        }
        androidInstrumentedTest {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.mockk.android)
                implementation(libs.androidx.junit)
                implementation(libs.androidx.rules)
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

android {
    namespace = project.group.toString()
    compileSdk = extra["targetSdk"] as Int
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")

    defaultConfig {
        minSdk = extra["minSdk"] as Int

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        consumerProguardFiles("proguard-rules.pro")
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
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

// Do not generate Intrinsics runtime assertion for performance reasons
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class)
    .all {
        compilerOptions {
            freeCompilerArgs.addAll(
                listOf(
                    "-Xno-call-assertions",
                    "-Xno-receiver-assertions",
                    "-Xno-param-assertions"
                )
            )
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
    moduleName.set("WorldWind Kotlin")
    pluginsConfiguration.html {
        footerMessage.set("(c) WorldWind Earth")
    }
    dokkaPublications.html {
        outputDirectory.set(dokkaOutputDir)
    }
}

val sonatypeUsername: String? = System.getenv("SONATYPE_USERNAME")
val sonatypePassword: String? = System.getenv("SONATYPE_PASSWORD")
publishing {
    publications {
        repositories {
            maven {
                name="oss"
                val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
                credentials {
                    username = sonatypeUsername
                    password = sonatypePassword
                }
            }
        }
        withType<MavenPublication> {
            artifact(javadocJar)
            pom {
                name.set("WorldWind Kotlin")
                description.set("The WorldWind Kotlin SDK (WWK) includes the library, examples and tutorials for building multiplatform 3D virtual globe applications for Android, Web and Java.")
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
    sign(publishing.publications)
}

// https://github.com/gradle/gradle/issues/26091
tasks.withType<AbstractPublishToMaven>().configureEach {
    val signingTasks = tasks.withType<Sign>()
    mustRunAfter(signingTasks)
}