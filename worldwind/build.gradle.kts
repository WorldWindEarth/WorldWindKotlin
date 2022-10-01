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
    multiplatformResourcesPackage = project.group.toString()
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        testRuns["test"].executionTask.configure {
            useJUnit()
        }
    }
    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport.enabled = true
            }
        }
    }
    android {
        publishLibraryVariants("release")
    }
    sourceSets {
        val mockkVersion = "1.13.1"
        val mokoVersion = "0.20.1"
        val ktorVersion = "2.1.1"
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.github.pdvrieze.xmlutil:serialization:0.84.2")
                implementation("com.eygraber:uri-kmp:0.0.9")
                api("dev.icerock.moko:resources:$mokoVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("dev.icerock.moko:resources-test:$mokoVersion")
            }
        }
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                // TODO Migrate to CIO engine when issue with connection timeouts on emulator will be solved
                implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
            }
        }
        val jvmCommonTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("io.mockk:mockk-jvm:$mockkVersion")
            }
        }
        val jvmMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                val joglVersion = "2.3.2"
                implementation("org.jogamp.gluegen:gluegen-rt:$joglVersion")
                implementation("org.jogamp.jogl:jogl-all:$joglVersion")

                val lwjglVersion = "3.3.1"
                implementation("org.lwjgl:lwjgl:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-assimp:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-openal:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-stb:$lwjglVersion")
            }
        }
        val jvmTest by getting {
            dependsOn(jvmCommonTest)
        }
        val jsMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-js:$ktorVersion")
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation("androidx.annotation:annotation:1.4.0")
                implementation("androidx.appcompat:appcompat-resources:1.5.1")
            }
        }
        val androidTest by getting {
            dependsOn(jvmCommonTest)
        }
        val androidAndroidTestRelease by getting
        val androidTestFixtures by getting
        val androidTestFixturesDebug by getting
        val androidTestFixturesRelease by getting
        val androidAndroidTest by getting {
            dependsOn(androidAndroidTestRelease) // Workaround to avoid Gradle build warning
            dependsOn(androidTestFixtures) // Workaround to avoid Gradle build warning
            dependsOn(androidTestFixturesDebug) // Workaround to avoid Gradle build warning
            dependsOn(androidTestFixturesRelease) // Workaround to avoid Gradle build warning
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("io.mockk:mockk-android:$mockkVersion")
                implementation("androidx.test.ext:junit:1.1.3")
                implementation("androidx.test:rules:1.4.0")
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
        targetSdk = extra["targetSdk"] as Int

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"))
        }
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

val dokkaOutputDir = "$buildDir/dokka"
tasks.getByName<org.jetbrains.dokka.gradle.DokkaTask>("dokkaHtml") {
    outputDirectory.set(file(dokkaOutputDir))
}
val deleteDokkaOutputDir by tasks.register<Delete>("deleteDokkaOutputDirectory") {
    delete(dokkaOutputDir)
}
val javadocJar = tasks.register<Jar>("javadocJar") {
    dependsOn(deleteDokkaOutputDir, tasks.dokkaHtml)
    archiveClassifier.set("javadoc")
    from(dokkaOutputDir)
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