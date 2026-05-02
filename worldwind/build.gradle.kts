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
                implementation(libs.geojson)
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
                implementation(libs.geopackage.core.get().toString()) {
                    exclude(group = "com.j256.ormlite")
                }
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

                implementation(libs.mil.sym.java)
                implementation(libs.geopackage.java)
                implementation(libs.ormlite.jdbc)

                implementation(libs.kotlinx.coroutines.swing)

                // Optional video-texture backends. All compileOnly: heavyweight (each pulls
                // ~200 MB+ of native binaries), and apps choose at most one or two. Apps that
                // want video texturing must add the matching dependency themselves.
                //
                // VLCJ — libVLC bindings, requires VLC 3.0+ installed on the host.
                compileOnly(libs.vlcj)
                // JavaCV / FFmpeg via javacpp-presets — bundles its own ffmpeg natives.
                // Used by both JavaCvVideoTexture (high-level FFmpegFrameGrabber) and
                // FFmpegVideoTexture (raw avformat/avcodec/swscale).
                compileOnly(libs.javacv.platform)
                // JavaFX Media — bundles its own gstreamer-based media decoder. Used by
                // JavaFxVideoTexture (off-screen MediaView snapshot loop). The no-classifier
                // openjfx JARs on Maven Central are empty placeholders; the actual classes
                // live in the platform-classified JARs (any platform has the same classes,
                // they only differ in bundled native libs). Pick the host classifier so
                // the engine compiles wherever it's built.
                val javafxVersion = libs.versions.javafx.get()
                val javafxPlatform = when {
                    System.getProperty("os.name").lowercase().contains("win") -> "win"
                    System.getProperty("os.name").lowercase().contains("mac") -> "mac"
                    else -> "linux"
                }
                compileOnly("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
                compileOnly("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
                compileOnly("org.openjfx:javafx-media:$javafxVersion:$javafxPlatform")
                compileOnly("org.openjfx:javafx-swing:$javafxVersion:$javafxPlatform")
            }
        }
        jvmTest {
            dependsOn(jvmCommonTest)
        }
        jsMain {
            dependencies {
                implementation(libs.ktor.client.js)
                implementation(project.dependencies.platform(libs.kotlin.wrappers.bom))
                implementation(libs.kotlin.browser)
                implementation(npm("canvg", ">= 4.0.3"))
                implementation(npm("@armyc2.c5isr.renderer/mil-sym-ts-web", libs.versions.mil.sym.ts.get()))
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
                implementation(libs.geopackage.android)
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
                    optIn.add("kotlin.time.ExperimentalTime")
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

// Karakum's Java-style KDoc refs in C5Ren.kt fail Dokka link resolution. Stage a copy with
// those KDoc blocks stripped and feed it to Dokka instead of the original.
val dokkaJsSourcesDir = layout.buildDirectory.dir("dokkaSources/jsMain")
val prepareDokkaJsSources by tasks.registering(Sync::class) {
    from(file("src/jsMain/kotlin"))
    into(dokkaJsSourcesDir)
    doLast {
        val c5ren = dokkaJsSourcesDir.get().file("earth/worldwind/shape/milstd2525/C5Ren.kt").asFile
        if (c5ren.exists()) {
            c5ren.writeText(c5ren.readText().replace(Regex("""/\*\*[\s\S]*?\*/\s*"""), ""))
        }
    }
}

dokka {
    moduleName.set("WorldWind Kotlin")
    pluginsConfiguration.html { footerMessage.set("(c) WorldWind Earth") }
    dokkaPublications.html { outputDirectory.set(dokkaOutputDir) }
    dokkaSourceSets.named("jsMain") {
        sourceRoots.setFrom(dokkaJsSourcesDir)
        // C5Ren.kt is JS bindings only — keep it out of the published output too.
        suppressedFiles.from(dokkaJsSourcesDir.map { it.file("earth/worldwind/shape/milstd2525/C5Ren.kt") })
    }
}

tasks.named("dokkaGeneratePublicationHtml") { dependsOn(prepareDokkaJsSources) }

publishing {
    publications {
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

/**
 * Build-time guard: refuse to compile shader programs that contain non-ASCII bytes inside
 * their `programSources` triple-quoted strings. GLSL ES drivers are spec-permitted to
 * silently fail to compile sources containing bytes > 0x7F, which produces empty programs
 * and transparent draws with no compiler error log. Catching this at build time means we
 * never ship a shader source that's invisibly broken on a subset of platforms.
 *
 * Scope: matches `*ShaderProgram*.kt` and `*Glsl*.kt` under `commonMain/kotlin` (the latter
 * captures shared GLSL helpers like `ShadowReceiverGlsl` whose triple-quoted bodies are
 * `${...}`-interpolated into shader programs and reach the GLSL compiler the same way).
 * Triple-quoted strings inside those files are scanned for any byte outside the printable
 * ASCII range. Comments and KDoc OUTSIDE the strings are left alone (they never reach the
 * GLSL compiler). Failure prints the file, line number, and offending substring.
 */
val checkShaderSourcesAscii by tasks.registering {
    val shaderFiles = fileTree("src/commonMain/kotlin") {
        include("**/*ShaderProgram*.kt", "**/*Glsl*.kt")
    }
    inputs.files(shaderFiles)
    doLast {
        val violations = mutableListOf<String>()
        shaderFiles.forEach { file ->
            // Cheap heuristic: walk the file character-by-character with a single boolean
            // tracking whether we're inside a triple-quoted string. That's enough to
            // separate KDoc / comments / Kotlin code from the shader-source payloads we
            // actually care about, without pulling in a full Kotlin parser.
            val text = file.readText()
            var inTripleQuote = false
            var line = 1
            var col = 1
            var i = 0
            while (i < text.length) {
                if (!inTripleQuote && i + 2 < text.length && text[i] == '"' && text[i+1] == '"' && text[i+2] == '"') {
                    inTripleQuote = true; i += 3; col += 3; continue
                }
                if (inTripleQuote && i + 2 < text.length && text[i] == '"' && text[i+1] == '"' && text[i+2] == '"') {
                    inTripleQuote = false; i += 3; col += 3; continue
                }
                val c = text[i]
                if (inTripleQuote && c.code > 0x7F) {
                    violations += "${file.path}:$line:$col  non-ASCII U+${"%04X".format(c.code)} ('$c') in shader source"
                }
                if (c == '\n') { line++; col = 1 } else col++
                i++
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Non-ASCII characters in GLSL shader sources (will silently fail on some drivers):\n  " +
                    violations.joinToString("\n  ")
            )
        }
    }
}

tasks.named("compileKotlinJvm") { dependsOn(checkShaderSourcesAscii) }
tasks.named("compileKotlinJs") { dependsOn(checkShaderSourcesAscii) }
tasks.matching { it.name == "compileDebugKotlinAndroid" || it.name == "compileReleaseKotlinAndroid" }
    .configureEach { dependsOn(checkShaderSourcesAscii) }

// https://github.com/gradle/gradle/issues/26091
tasks.withType<AbstractPublishToMaven>().configureEach {
    val signingTasks = tasks.withType<Sign>()
    mustRunAfter(signingTasks)
}