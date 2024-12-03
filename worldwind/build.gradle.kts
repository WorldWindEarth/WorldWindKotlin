plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("dev.icerock.mobile.multiplatform-resources")
    id("worldwind.maven-publish")
}

multiplatformResources {
    resourcesPackage.set(project.group.toString())
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = extra["javaVersion"].toString()
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
        compilations.all {
            kotlinOptions.jvmTarget = extra["javaVersion"].toString()
        }
    }
    sourceSets {
        val mockkVersion = "1.13.13"
        val mokoVersion = "0.24.3"
        val ktorVersion = "2.3.12"
        val ormliteVersion = "6.1"
        commonMain {
            dependencies {
                implementation(project(":worldwind-util-format"))
                implementation(project(":worldwind-util-glu"))
                implementation(project(":worldwind-util-kgl"))
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.github.pdvrieze.xmlutil:serialization:0.90.3")
                implementation("com.eygraber:uri-kmp:0.0.18")
                implementation("ar.com.hjg:pngj:2.1.0")
                implementation("mil.nga:tiff:3.0.0")
                api("dev.icerock.moko:resources:$mokoVersion")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("dev.icerock.moko:resources-test:$mokoVersion")
            }
        }
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
                compileOnly("com.j256.ormlite:ormlite-core:$ormliteVersion")
            }
        }
        val jvmCommonTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("io.mockk:mockk-jvm:$mockkVersion")
            }
        }
        jvmMain {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation("io.github.missioncommand:mil-sym-renderer:0.1.41")
                implementation("com.j256.ormlite:ormlite-jdbc:$ormliteVersion")
            }
        }
        jvmTest {
            dependsOn(jvmCommonTest)
        }
        jsMain {
            dependencies {
                implementation("io.ktor:ktor-client-js:$ktorVersion")
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
                implementation("androidx.annotation:annotation:1.9.1")
                implementation("androidx.appcompat:appcompat-resources:1.7.0")
                implementation("io.github.missioncommand:mil-sym-android-renderer:0.1.60")
                implementation("com.j256.ormlite:ormlite-android:$ormliteVersion")
            }
        }
        androidUnitTest {
            dependsOn(jvmCommonTest)
        }
        androidInstrumentedTest {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("io.mockk:mockk-android:$mockkVersion")
                implementation("androidx.test.ext:junit:1.2.1")
                implementation("androidx.test:rules:1.6.1")
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
        sourceCompatibility = extra["javaVersion"] as JavaVersion
        targetCompatibility = extra["javaVersion"] as JavaVersion
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
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
