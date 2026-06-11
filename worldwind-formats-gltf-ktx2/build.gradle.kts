import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.FileNotFoundException
import java.util.Properties

// ──────────────────────────────────────────────────────────────────────────────────────
// worldwind-formats-gltf-ktx2 — KHR_texture_basisu glTF extension decoder.
// Wraps Khronos' open-source libktx (KTX-Software, Apache 2.0); same per-platform
// shape as worldwind-formats-gltf-draco. Compile decode output is RGBA8888.
// ──────────────────────────────────────────────────────────────────────────────────────

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("maven-publish")
    id("signing")
    id("org.jetbrains.dokka")
}

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

val iosNativeOptIn = providers.gradleProperty("worldwind.ktx2.buildIosNative").orNull == "true"
val androidNativeOptIn = providers.gradleProperty("worldwind.ktx2.buildAndroidNative").orNull == "true"
val androidNdkInstalled = sdkDir.orNull?.let { sdk ->
    file("$sdk/ndk/$ndkVersion").exists() && file("$sdk/cmake/$cmakeVersion/bin/cmake").exists()
} ?: false
val jvmNativeOptIn = providers.gradleProperty("worldwind.ktx2.buildJvmNative").orNull == "true"
val cmakeOnPath = providers.exec {
    isIgnoreExitValue = true
    commandLine("sh", "-c", "command -v cmake")
}.result.get().exitValue == 0

fun KotlinNativeTarget.setupIosKtx2Cinterop(
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
        executable = "cmake"
        argumentProviders.add {
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
        description = "Build libktx_bridge.a + libktx_read.a for iOS $cmakeTarget."
        dependsOn(cmakeConfigure)
        inputs.files(fileTree("src/iosMain/cpp"))
        outputs.file(buildDirProv.map { it.file("libktx_bridge.a") })
        workingDir = buildDirProv.get().asFile
        executable = "cmake"
        argumentProviders.add {
            listOf("--build", buildDirProv.get().asFile.absolutePath, "--config", "Release")
        }
    }

    compilations.named("main") {
        val ktx = cinterops.create("ktx") {
            definitionFile.set(file("src/iosMain/cinterop/ktx.def"))
            includeDirs(file("src/iosMain/cpp"))
            extraOpts(
                "-libraryPath", buildDirProv.get().asFile.absolutePath,
                "-libraryPath", buildDirProv.get().dir("_deps/ktx-build").asFile.absolutePath,
            )
        }
        tasks.named(ktx.interopProcessingTaskName).configure { dependsOn(cmakeBuild) }
    }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    js { browser {} }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser {} }
    iosX64 { if (iosNativeOptIn) setupIosKtx2Cinterop("ios_x64", "iphonesimulator", "x86_64") }
    iosArm64 { if (iosNativeOptIn) setupIosKtx2Cinterop("ios_arm64", "iphoneos", "arm64") }
    iosSimulatorArm64 { if (iosNativeOptIn) setupIosKtx2Cinterop("ios_simulator_arm64", "iphonesimulator", "arm64") }
    @Suppress("UnstableApiUsage")
    android {
        namespace = "earth.worldwind.formats.gltf.ktx2"
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
            resources.srcDir(jvmResourcesNativeDir)
        }
        getByName("webMain") {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                implementation(libs.kotlinx.browser)
                // THREE.js ships the canonical Google basis_transcoder JS+WASM pair at
                // examples/jsm/libs/basis/. We only require the two transcoder files —
                // webpack tree-shakes the rest of THREE.js out of the bundle.
                implementation(npm("three", "0.160.0"))
            }
        }
        jsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
        // iOS swap: -Pworldwind.ktx2.buildIosNative=true REPLACES the stub directory
        // (src/iosMain/) with the cinterop-backed impl (src/iosMainNative/). Both files
        // declare the same `actual` symbols, so this must be setSrcDirs, not srcDir.
        named("iosMain") {
            if (iosNativeOptIn) kotlin.setSrcDirs(listOf("src/iosMainNative/kotlin"))
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────────────
// Android native build — per ABI. Output: libktx_bridge.so containing libktx + our JNI wrapper.
// ──────────────────────────────────────────────────────────────────────────────────────

val buildAbiTasks = androidAbis.map { abi ->
    val safeName = abi.replace('-', '_')
    val ndkDir = sdkDir.map { "$it/ndk/$ndkVersion" }
    val cmakeBinDir = sdkDir.map { "$it/cmake/$cmakeVersion/bin" }
    val buildDirProv = androidNativeBuildRoot.map { it.dir(abi) }

    val configure = tasks.register<Exec>("cmakeConfigure_$safeName") {
        group = "native"
        description = "CMake configure for $abi."
        inputs.files(fileTree("src/androidMain/cpp"))
        inputs.files(fileTree("src/jvmCommonMain/cpp"))
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
        description = "CMake build of libktx_bridge.so for $abi."
        dependsOn(configure)
        inputs.files(fileTree("src/androidMain/cpp"))
        inputs.files(fileTree("src/jvmCommonMain/cpp"))
        outputs.file(buildDirProv.map { it.file("libktx_bridge.so") })
        workingDir = buildDirProv.get().asFile
        executable = cmakeBinDir.map { "$it/cmake" }.get()
        argumentProviders.add {
            listOf("--build", buildDirProv.get().asFile.absolutePath, "--config", "Release")
        }
    }

    tasks.register<Copy>("buildKtx2Bridge_$safeName") {
        group = "native"
        description = "Copy libktx_bridge.so for $abi into jniLibs."
        dependsOn(build)
        from(buildDirProv.map { it.file("libktx_bridge.so") })
        into(androidJniLibsOutputDir.map { it.dir(abi) })
    }
}

val buildKtx2Bridge by tasks.registering {
    group = "native"
    description = "Build libktx_bridge.so for every supported Android ABI."
    dependsOn(buildAbiTasks)
}

@Suppress("UnstableApiUsage")
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(androidJniLibsOutputDir.get().asFile.absolutePath)
    }
}

// ──────────────────────────────────────────────────────────────────────────────────────
// JVM native build — for the current host.
// ──────────────────────────────────────────────────────────────────────────────────────

data class JvmHost(val classifier: String, val libFileName: String)

val jvmHost: JvmHost by lazy {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val arch = if ("aarch64" in osArch || "arm64" in osArch) "aarch64" else "x86_64"
    when {
        "mac" in osName || "darwin" in osName -> JvmHost("macos-$arch", "libktx_bridge.dylib")
        "linux" in osName -> JvmHost("linux-$arch", "libktx_bridge.so")
        "windows" in osName -> JvmHost("windows-x86_64", "ktx_bridge.dll")
        else -> error("worldwind-formats-gltf-ktx2: unsupported JVM host $osName / $osArch")
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
    executable = "cmake"
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
    description = "CMake build of libktx_bridge for the JVM host."
    dependsOn(cmakeConfigureJvm)
    inputs.files(fileTree("src/jvmMain/cpp"))
    inputs.files(fileTree("src/jvmCommonMain/cpp"))
    val targetDir = jvmNativeBuildDir.map { it.dir(jvmHost.classifier) }
    outputs.file(targetDir.map { it.file(jvmHost.libFileName) })
    workingDir = targetDir.get().asFile
    executable = "cmake"
    argumentProviders.add {
        listOf("--build", targetDir.get().asFile.absolutePath, "--config", "Release")
    }
}

val buildKtx2BridgeJvm by tasks.registering(Copy::class) {
    group = "native"
    description = "Stage the host's libktx_bridge into JVM resources (native/<classifier>/...)."
    dependsOn(cmakeBuildJvm)
    from(jvmNativeBuildDir.map { it.dir(jvmHost.classifier).file(jvmHost.libFileName) })
    into(jvmResourcesNativeDir.map { it.dir("native/${jvmHost.classifier}") })
}

afterEvaluate {
    if (androidNativeOptIn || androidNdkInstalled) {
        tasks.matching {
            it.name.startsWith("mergeAndroid") || it.name.startsWith("assemble") || it.name.startsWith("bundle")
        }.configureEach { dependsOn(buildKtx2Bridge) }
    } else {
        logger.lifecycle(
            "worldwind-formats-gltf-ktx2: NDK $ndkVersion + CMake $cmakeVersion not found — APK " +
                "will ship without libktx_bridge.so. Install via Android Studio's SDK Manager or " +
                "set -Pworldwind.ktx2.buildAndroidNative=true."
        )
    }
    if (jvmNativeOptIn || cmakeOnPath) {
        tasks.named("jvmProcessResources") { dependsOn(buildKtx2BridgeJvm) }
        tasks.matching { it.name == "jvmJar" || it.name == "jvmSourcesJar" }
            .configureEach { dependsOn(buildKtx2BridgeJvm) }
    } else {
        logger.lifecycle(
            "worldwind-formats-gltf-ktx2: cmake not found on PATH — JVM JAR will ship without " +
                "native libktx_bridge. Install cmake or set -Pworldwind.ktx2.buildJvmNative=true."
        )
    }
}
