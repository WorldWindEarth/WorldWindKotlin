import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.FileNotFoundException
import java.util.Properties

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("maven-publish")
    id("signing")
    id("org.jetbrains.dokka")
}

// Codec modules version independently of the main engine — bumped via
// `worldwind.codecsVersion` in gradle.properties.
version = providers.gradleProperty("worldwind.codecsVersion").get()

// ──────────────────────────────────────────────────────────────────────────────────────
// Build-config constants — declared at file scope so they're available both to the
// kotlin { } target configuration (iOS cinterop setup) and to the native-build Exec
// tasks declared at the bottom.
// ──────────────────────────────────────────────────────────────────────────────────────

val ndkVersion = "30.0.14904198"
val cmakeVersion = "4.1.2"
val androidApi = providers.gradleProperty("worldwind.minSdk").get().toInt()
val androidAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

val sdkDir: Provider<String> = providers.provider {
    System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
            Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }
        ?: throw FileNotFoundException(
            "Could not find Android SDK. Set ANDROID_HOME or add sdk.dir=... to local.properties."
        )
}

val androidNativeBuildRoot = layout.buildDirectory.dir("native-android")
val androidJniLibsOutputDir = layout.buildDirectory.dir("jniLibs")

val iosNativeBuildRoot = layout.buildDirectory.dir("native-ios")

val jvmNativeBuildDir = layout.buildDirectory.dir("native-jvm")
val jvmResourcesNativeDir = layout.buildDirectory.dir("generated/jvmNative")

// Opt-in flags — declared above `kotlin { }` so the iOS cinterop hook + the iosMain
// source-dir swap can read them. (When declared after the block, Gradle Kotlin DSL reads
// the uninitialised field as `false`, silently disabling the opt-in.)
val iosNativeOptIn = providers.gradleProperty("worldwind.draco.buildIosNative").orNull == "true"
val androidNativeOptIn = providers.gradleProperty("worldwind.draco.buildAndroidNative").orNull == "true"
val androidNdkInstalled = sdkDir.orNull?.let { sdk ->
    file("$sdk/ndk/$ndkVersion").exists() && file("$sdk/cmake/$cmakeVersion/bin/cmake").exists()
} ?: false
val jvmNativeOptIn = providers.gradleProperty("worldwind.draco.buildJvmNative").orNull == "true"
val cmakeOnPath = providers.exec {
    isIgnoreExitValue = true
    commandLine("sh", "-c", "command -v cmake")
}.result.get().exitValue == 0

// cmake from PATH, else the Android SDK's bundled copy — no system-wide install required.
val cmakeExecutable: String = if (cmakeOnPath) "cmake"
    else sdkDir.orNull?.let { sdk -> file("$sdk/cmake/$cmakeVersion/bin/cmake").takeIf { it.exists() }?.absolutePath }
        ?: "cmake"

// ──────────────────────────────────────────────────────────────────────────────────────
// iOS cinterop helper — registers cmakeConfigureIos_<target> + cmakeBuildIos_<target>
// Exec tasks, then attaches a cinterop binding that depends on the CMake build. Called
// from each KotlinNativeTarget (iosArm64 / iosSimulatorArm64) below.
// ──────────────────────────────────────────────────────────────────────────────────────

fun KotlinNativeTarget.setupIosDracoCinterop(
    cmakeTarget: String,
    appleSdk: String,
    appleArch: String,
) {
    val buildDirProv = iosNativeBuildRoot.map { it.dir(cmakeTarget) }

    val cmakeConfigure = tasks.register<Exec>("cmakeConfigureIos_$cmakeTarget") {
        group = "native"
        description = "CMake configure for iOS $cmakeTarget."
        inputs.files(fileTree("src/iosMain/cpp"))
        outputs.file(buildDirProv.map { it.file("CMakeCache.txt") })
        doFirst { buildDirProv.get().asFile.mkdirs() }
        workingDir = buildDirProv.get().asFile
        executable = cmakeExecutable
        argumentProviders.add {
            // Resolve the Apple SDK path via xcrun. Minimum iOS version aligns with the
            // worldwind module's iOS deployment target.
            val sdkPath = providers.exec {
                commandLine("xcrun", "--show-sdk-path", "--sdk", appleSdk)
            }.standardOutput.asText.get().trim()
            listOf(
                "-G", "Unix Makefiles",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_SYSTEM_NAME=iOS",
                "-DCMAKE_OSX_SYSROOT=$sdkPath",
                "-DCMAKE_OSX_ARCHITECTURES=$appleArch",
                "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
                "-S", file("src/iosMain/cpp").absolutePath,
                "-B", buildDirProv.get().asFile.absolutePath,
            )
        }
    }

    val cmakeBuild = tasks.register<Exec>("cmakeBuildIos_$cmakeTarget") {
        group = "native"
        description = "Build libdraco_bridge.a + libdraco.a for iOS $cmakeTarget."
        dependsOn(cmakeConfigure)
        inputs.files(fileTree("src/iosMain/cpp"))
        outputs.file(buildDirProv.map { it.file("libdraco_bridge.a") })
        outputs.file(buildDirProv.map { it.dir("_deps/draco-build/libdraco.a") })
        workingDir = buildDirProv.get().asFile
        executable = cmakeExecutable
        argumentProviders.add {
            listOf("--build", buildDirProv.get().asFile.absolutePath, "--config", "Release")
        }
    }

    compilations.named("main") {
        val draco = cinterops.create("draco") {
            definitionFile.set(file("src/iosMain/cinterop/draco.def"))
            includeDirs(file("src/iosMain/cpp"))
            extraOpts(
                "-libraryPath", buildDirProv.get().asFile.absolutePath,
                "-libraryPath", buildDirProv.get().dir("_deps/draco-build").asFile.absolutePath,
            )
        }
        tasks.named(draco.interopProcessingTaskName).configure { dependsOn(cmakeBuild) }
    }
}

// ──────────────────────────────────────────────────────────────────────────────────────
// Kotlin Multiplatform targets.
// ──────────────────────────────────────────────────────────────────────────────────────

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    js {
        browser {}
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {}
    }
    iosArm64 { if (iosNativeOptIn) setupIosDracoCinterop("ios_arm64", "iphoneos", "arm64") }
    iosSimulatorArm64 { if (iosNativeOptIn) setupIosDracoCinterop("ios_simulator_arm64", "iphonesimulator", "arm64") }
    @Suppress("UnstableApiUsage")
    android {
        namespace = "earth.worldwind.formats.gltf.draco"
        compileSdk = providers.gradleProperty("worldwind.targetSdk").get().toInt()
        minSdk = providers.gradleProperty("worldwind.minSdk").get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("web") {
                withJs()
                withWasmJs()
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":worldwind"))
            }
        }
        androidMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
            }
        }
        named("jvmMain") {
            // Make Gradle's JAR task pick up build/generated/jvmNative as a resource root.
            resources.srcDir(jvmResourcesNativeDir)
        }
        named("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // iOS source-set swap: opt-out by default ships a no-op stub at src/iosMain/;
        // -Pworldwind.draco.buildIosNative=true REPLACES the stub directory with the
        // cinterop-backed implementation under src/iosMainNative/. Both files declare the
        // same `actual` symbols, so we have to swap (setSrcDirs), not add (srcDir).
        named("iosMain") {
            if (iosNativeOptIn) kotlin.setSrcDirs(listOf("src/iosMainNative/kotlin"))
        }
        getByName("webMain") {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }
        jsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                implementation(npm("draco3d", "1.5.7"))
            }
        }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                implementation(npm("draco3d", "1.5.7"))
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────────────
// Android native build — per ABI via NDK + CMake.
//
// AGP 9.2's `com.android.kotlin.multiplatform.library` plugin doesn't yet surface
// `externalNativeBuild` in its DSL. Until it lands we drive CMake by hand via Exec tasks
// and feed the resulting .so files into jniLibs via androidComponents.
// ──────────────────────────────────────────────────────────────────────────────────────

val buildAbiTasks = androidAbis.map { abi ->
    val safeName = abi.replace('-', '_')
    val ndkDir = sdkDir.map { "$it/ndk/$ndkVersion" }
    val cmakeBinDir = sdkDir.map { "$it/cmake/$cmakeVersion/bin" }
    val buildDirProv = androidNativeBuildRoot.map { it.dir(abi) }
    val outputSo = androidJniLibsOutputDir.map { it.file("$abi/libdraco_bridge.so") }

    val configure = tasks.register<Exec>("cmakeConfigure_$safeName") {
        group = "native"
        description = "CMake configure for $abi."
        inputs.files(fileTree("src/androidMain/cpp"))
        inputs.files(fileTree("src/jvmCommonMain/cpp"))
        inputs.property("ndkVersion", ndkVersion)
        inputs.property("androidApi", androidApi)
        outputs.file(buildDirProv.map { it.file("CMakeCache.txt") })
        doFirst { buildDirProv.get().asFile.mkdirs() }
        workingDir = buildDirProv.get().asFile
        executable = cmakeBinDir.map { "$it/cmake" }.get()
        argumentProviders.add {
            listOf(
                "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=${cmakeBinDir.get()}/ninja",
                "-DCMAKE_TOOLCHAIN_FILE=${ndkDir.get()}/build/cmake/android.toolchain.cmake",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-$androidApi",
                "-DCMAKE_BUILD_TYPE=Release",
                "-S", file("src/androidMain/cpp").absolutePath,
                "-B", buildDirProv.get().asFile.absolutePath,
            )
        }
    }

    val build = tasks.register<Exec>("cmakeBuild_$safeName") {
        group = "native"
        description = "CMake build of libdraco_bridge.so for $abi."
        dependsOn(configure)
        inputs.files(fileTree("src/androidMain/cpp"))
        inputs.files(fileTree("src/jvmCommonMain/cpp"))
        outputs.file(buildDirProv.map { it.file("libdraco_bridge.so") })
        workingDir = buildDirProv.get().asFile
        executable = cmakeBinDir.map { "$it/cmake" }.get()
        argumentProviders.add {
            listOf("--build", buildDirProv.get().asFile.absolutePath, "--config", "Release")
        }
    }

    tasks.register<Copy>("buildDracoBridge_$safeName") {
        group = "native"
        description = "Copy libdraco_bridge.so for $abi into jniLibs."
        dependsOn(build)
        from(buildDirProv.map { it.file("libdraco_bridge.so") })
        into(androidJniLibsOutputDir.map { it.dir(abi) })
        outputs.file(outputSo)
    }
}

val buildDracoBridge = tasks.register("buildDracoBridge") {
    group = "native"
    description = "Build libdraco_bridge.so for every supported Android ABI."
    dependsOn(buildAbiTasks)
}

@Suppress("UnstableApiUsage")
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(androidJniLibsOutputDir.get().asFile.absolutePath)
    }
}

// ──────────────────────────────────────────────────────────────────────────────────────
// JVM native build — for the current host (Linux x86_64, macOS arm64/x86_64, Windows
// x86_64). Uses system `cmake`. Output lands in JAR resources under `native/<classifier>/`.
// ──────────────────────────────────────────────────────────────────────────────────────

data class JvmHost(val classifier: String, val libFileName: String)

val jvmHost: JvmHost by lazy {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val arch = if ("aarch64" in osArch || "arm64" in osArch) "aarch64" else "x86_64"
    when {
        "mac" in osName || "darwin" in osName -> JvmHost("macos-$arch", "libdraco_bridge.dylib")
        "linux" in osName -> JvmHost("linux-$arch", "libdraco_bridge.so")
        "windows" in osName -> JvmHost("windows-x86_64", "draco_bridge.dll")
        else -> error("worldwind-formats-gltf-draco: unsupported JVM host $osName / $osArch")
    }
}

val cmakeConfigureJvm = tasks.register<Exec>("cmakeConfigureJvm") {
    group = "native"
    description = "CMake configure for the JVM host."
    inputs.files(fileTree("src/jvmMain/cpp"))
    inputs.files(fileTree("src/jvmCommonMain/cpp"))
    outputs.file(jvmNativeBuildDir.map { it.file("${jvmHost.classifier}/CMakeCache.txt") })
    doFirst { jvmNativeBuildDir.get().dir(jvmHost.classifier).asFile.mkdirs() }
    workingDir = jvmNativeBuildDir.get().dir(jvmHost.classifier).asFile
    executable = cmakeExecutable
    argumentProviders.add {
        listOf(
            "-DCMAKE_BUILD_TYPE=Release",
            "-S", file("src/jvmMain/cpp").absolutePath,
            "-B", jvmNativeBuildDir.get().dir(jvmHost.classifier).asFile.absolutePath,
        )
    }
}

val cmakeBuildJvm = tasks.register<Exec>("cmakeBuildJvm") {
    group = "native"
    description = "CMake build of libdraco_bridge.{so,dylib,dll} for the JVM host."
    dependsOn(cmakeConfigureJvm)
    inputs.files(fileTree("src/jvmMain/cpp"))
    inputs.files(fileTree("src/jvmCommonMain/cpp"))
    val targetDir = jvmNativeBuildDir.map { it.dir(jvmHost.classifier) }
    outputs.file(targetDir.map { it.file(jvmHost.libFileName) })
    workingDir = targetDir.get().asFile
    executable = cmakeExecutable
    argumentProviders.add {
        listOf("--build", targetDir.get().asFile.absolutePath, "--config", "Release")
    }
}

val buildDracoBridgeJvm = tasks.register<Copy>("buildDracoBridgeJvm") {
    group = "native"
    description = "Stage the host's libdraco_bridge into JVM resources (native/<classifier>/...)."
    dependsOn(cmakeBuildJvm)
    from(jvmNativeBuildDir.map { it.dir(jvmHost.classifier).file(jvmHost.libFileName) })
    into(jvmResourcesNativeDir.map { it.dir("native/${jvmHost.classifier}") })
}

// ──────────────────────────────────────────────────────────────────────────────────────
// Toolchain-detection gates. Each platform's native build is wired into packaging only
// when its toolchain is available (or the user explicitly opts in). Without the
// toolchain the published artifacts ship without that platform's native binary; the
// Kotlin runtime fails-soft with an UnsatisfiedLinkError + clear log message at the call
// to installDracoDecoder().
// ──────────────────────────────────────────────────────────────────────────────────────

afterEvaluate {
    val skipLocalAndroidBuild = providers.gradleProperty("worldwind.publishingNativeStaging").orNull == "true"
    if (!skipLocalAndroidBuild && (androidNativeOptIn || androidNdkInstalled)) {
        tasks.matching {
            it.name.startsWith("mergeAndroid") || it.name.startsWith("assemble") || it.name.startsWith("bundle")
        }.configureEach { dependsOn(buildDracoBridge) }
    } else if (skipLocalAndroidBuild) {
        // staging mode handles Android via prebuilt jniLibs in build/jniLibs/
    } else {
        logger.lifecycle(
            "worldwind-formats-gltf-draco: NDK $ndkVersion + CMake $cmakeVersion not found under " +
                "the Android SDK — APK will ship without native libdraco_bridge. Install via " +
                "Android Studio's SDK Manager (NDK + CMake) or `sdkmanager \"ndk;$ndkVersion\" " +
                "\"cmake;$cmakeVersion\"`, or set -Pworldwind.draco.buildAndroidNative=true."
        )
    }
    // Publish-job override: when CI has already populated build/generated/jvmNative/ with
    // every host's binary from a matrix build, skip the local-host rebuild that
    // cmakeOnPath would otherwise trigger.
    val skipLocalNativeBuild = providers.gradleProperty("worldwind.publishingNativeStaging").orNull == "true"
    if (!skipLocalNativeBuild && (jvmNativeOptIn || cmakeOnPath)) {
        tasks.named("jvmProcessResources") { dependsOn(buildDracoBridgeJvm) }
        tasks.matching { it.name == "jvmJar" || it.name == "jvmSourcesJar" }
            .configureEach { dependsOn(buildDracoBridgeJvm) }
    } else if (skipLocalNativeBuild) {
        logger.lifecycle("worldwind-formats-gltf-draco: publishing-native-staging mode — using prebuilt binaries from build/generated/jvmNative/.")
    } else {
        logger.lifecycle(
            "worldwind-formats-gltf-draco: cmake not found on PATH — JVM JAR will ship without " +
                "native libdraco_bridge. Install cmake (brew install cmake / apt install cmake) " +
                "or set -Pworldwind.draco.buildJvmNative=true."
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────────────
// Publication metadata — see worldwind/build.gradle.kts for the canonical shape.
// ──────────────────────────────────────────────────────────────────────────────────────

val dokkaOutputDir = layout.buildDirectory.dir("dokka")
val deleteDokkaOutputDir = tasks.register<Delete>("deleteDokkaOutputDir") { delete(dokkaOutputDir) }
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
    moduleName.set("WorldWind Kotlin glTF Draco")
    pluginsConfiguration.html { footerMessage.set("(c) WorldWind Earth") }
    dokkaPublications.html { outputDirectory.set(dokkaOutputDir) }
}

publishing {
    publications {
        withType<MavenPublication> {
            // Full Dokka docs ship once on the root publication; targets carry an empty javadoc jar
            artifact(if (name == "kotlinMultiplatform") javadocJar else emptyJavadocJar)
            pom {
                name.set("WorldWind Kotlin glTF Draco Codec")
                description.set("KHR_draco_mesh_compression decoder satellite for WorldWind Kotlin — JNI/cinterop binding around libdraco. Registers with GltfDecoderRegistry on install.")
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
