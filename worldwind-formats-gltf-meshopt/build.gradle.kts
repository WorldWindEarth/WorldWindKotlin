import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.FileNotFoundException
import java.util.Properties

// ──────────────────────────────────────────────────────────────────────────────────────
// worldwind-formats-gltf-meshopt — EXT_meshopt_compression glTF extension decoder.
// Wraps Arseny Kapoulkine's open-source meshoptimizer (MIT). Decoder-only build; same
// per-platform shape as worldwind-formats-gltf-draco / -ktx2.
// ──────────────────────────────────────────────────────────────────────────────────────

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

val iosNativeOptIn = providers.gradleProperty("worldwind.meshopt.buildIosNative").orNull == "true"
val androidNativeOptIn = providers.gradleProperty("worldwind.meshopt.buildAndroidNative").orNull == "true"
val androidNdkInstalled = sdkDir.orNull?.let { sdk ->
    file("$sdk/ndk/$ndkVersion").exists() && file("$sdk/cmake/$cmakeVersion/bin/cmake").exists()
} ?: false
val jvmNativeOptIn = providers.gradleProperty("worldwind.meshopt.buildJvmNative").orNull == "true"
val cmakeOnPath = providers.exec {
    isIgnoreExitValue = true
    commandLine("sh", "-c", "command -v cmake")
}.result.get().exitValue == 0

fun KotlinNativeTarget.setupIosMeshoptCinterop(
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
        description = "Build libmeshopt_bridge.a + libmeshoptimizer.a for iOS $cmakeTarget."
        dependsOn(cmakeConfigure)
        inputs.files(fileTree("src/iosMain/cpp"))
        outputs.file(buildDirProv.map { it.file("libmeshopt_bridge.a") })
        workingDir = buildDirProv.get().asFile
        executable = "cmake"
        argumentProviders.add {
            listOf("--build", buildDirProv.get().asFile.absolutePath, "--config", "Release")
        }
    }

    compilations.named("main") {
        val meshopt = cinterops.create("meshopt") {
            definitionFile.set(file("src/iosMain/cinterop/meshopt.def"))
            includeDirs(file("src/iosMain/cpp"))
            extraOpts(
                "-libraryPath", buildDirProv.get().asFile.absolutePath,
                "-libraryPath", buildDirProv.get().dir("_deps/meshoptimizer-build").asFile.absolutePath,
            )
        }
        tasks.named(meshopt.interopProcessingTaskName).configure { dependsOn(cmakeBuild) }
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
    iosArm64 { if (iosNativeOptIn) setupIosMeshoptCinterop("ios_arm64", "iphoneos", "arm64") }
    iosSimulatorArm64 { if (iosNativeOptIn) setupIosMeshoptCinterop("ios_simulator_arm64", "iphonesimulator", "arm64") }
    @Suppress("UnstableApiUsage")
    android {
        namespace = "earth.worldwind.formats.gltf.meshopt"
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
            }
        }
        jsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                implementation(npm("meshoptimizer", "0.21.0"))
            }
        }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                implementation(npm("meshoptimizer", "0.21.0"))
            }
        }
        // iOS swap: -Pworldwind.meshopt.buildIosNative=true REPLACES the stub directory
        // (src/iosMain/) with the cinterop-backed impl (src/iosMainNative/). Both files
        // declare the same `actual` symbols, so this must be setSrcDirs, not srcDir.
        named("iosMain") {
            if (iosNativeOptIn) kotlin.setSrcDirs(listOf("src/iosMainNative/kotlin"))
        }
    }
}

// Android per-ABI native build.
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
        description = "CMake build of libmeshopt_bridge.so for $abi."
        dependsOn(configure)
        inputs.files(fileTree("src/androidMain/cpp"))
        inputs.files(fileTree("src/jvmCommonMain/cpp"))
        outputs.file(buildDirProv.map { it.file("libmeshopt_bridge.so") })
        workingDir = buildDirProv.get().asFile
        executable = cmakeBinDir.map { "$it/cmake" }.get()
        argumentProviders.add {
            listOf("--build", buildDirProv.get().asFile.absolutePath, "--config", "Release")
        }
    }

    tasks.register<Copy>("buildMeshoptBridge_$safeName") {
        group = "native"
        description = "Copy libmeshopt_bridge.so for $abi into jniLibs."
        dependsOn(build)
        from(buildDirProv.map { it.file("libmeshopt_bridge.so") })
        into(androidJniLibsOutputDir.map { it.dir(abi) })
    }
}

val buildMeshoptBridge by tasks.registering {
    group = "native"
    description = "Build libmeshopt_bridge.so for every supported Android ABI."
    dependsOn(buildAbiTasks)
}

@Suppress("UnstableApiUsage")
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(androidJniLibsOutputDir.get().asFile.absolutePath)
    }
}

// JVM host native build.
data class JvmHost(val classifier: String, val libFileName: String)

val jvmHost: JvmHost by lazy {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val arch = if ("aarch64" in osArch || "arm64" in osArch) "aarch64" else "x86_64"
    when {
        "mac" in osName || "darwin" in osName -> JvmHost("macos-$arch", "libmeshopt_bridge.dylib")
        "linux" in osName -> JvmHost("linux-$arch", "libmeshopt_bridge.so")
        "windows" in osName -> JvmHost("windows-x86_64", "meshopt_bridge.dll")
        else -> error("worldwind-formats-gltf-meshopt: unsupported JVM host $osName / $osArch")
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
    description = "CMake build of libmeshopt_bridge for the JVM host."
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

val buildMeshoptBridgeJvm by tasks.registering(Copy::class) {
    group = "native"
    description = "Stage the host's libmeshopt_bridge into JVM resources (native/<classifier>/...)."
    dependsOn(cmakeBuildJvm)
    from(jvmNativeBuildDir.map { it.dir(jvmHost.classifier).file(jvmHost.libFileName) })
    into(jvmResourcesNativeDir.map { it.dir("native/${jvmHost.classifier}") })
}

afterEvaluate {
    val skipLocalNativeBuild = providers.gradleProperty("worldwind.publishingNativeStaging").orNull == "true"
    if (!skipLocalNativeBuild && (androidNativeOptIn || androidNdkInstalled)) {
        tasks.matching {
            it.name.startsWith("mergeAndroid") || it.name.startsWith("assemble") || it.name.startsWith("bundle")
        }.configureEach { dependsOn(buildMeshoptBridge) }
    } else if (skipLocalNativeBuild) {
        // staging mode handles Android via prebuilt jniLibs in build/jniLibs/
    } else {
        logger.lifecycle(
            "worldwind-formats-gltf-meshopt: NDK $ndkVersion + CMake $cmakeVersion not found — APK " +
                "will ship without libmeshopt_bridge.so. Install via Android Studio's SDK Manager " +
                "or set -Pworldwind.meshopt.buildAndroidNative=true."
        )
    }
    if (!skipLocalNativeBuild && (jvmNativeOptIn || cmakeOnPath)) {
        tasks.named("jvmProcessResources") { dependsOn(buildMeshoptBridgeJvm) }
        tasks.matching { it.name == "jvmJar" || it.name == "jvmSourcesJar" }
            .configureEach { dependsOn(buildMeshoptBridgeJvm) }
    } else if (skipLocalNativeBuild) {
        logger.lifecycle("worldwind-formats-gltf-meshopt: publishing-native-staging mode — using prebuilt binaries from build/generated/jvmNative/.")
    } else {
        logger.lifecycle(
            "worldwind-formats-gltf-meshopt: cmake not found on PATH — JVM JAR will ship without " +
                "native libmeshopt_bridge. Install cmake or set -Pworldwind.meshopt.buildJvmNative=true."
        )
    }
}

val dokkaOutputDir = layout.buildDirectory.dir("dokka")
val deleteDokkaOutputDir by tasks.registering(Delete::class) { delete(dokkaOutputDir) }
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
    moduleName.set("WorldWind Kotlin glTF Meshopt")
    pluginsConfiguration.html { footerMessage.set("(c) WorldWind Earth") }
    dokkaPublications.html { outputDirectory.set(dokkaOutputDir) }
}

publishing {
    publications {
        withType<MavenPublication> {
            // Full Dokka docs ship once on the root publication; targets carry an empty javadoc jar
            artifact(if (name == "kotlinMultiplatform") javadocJar else emptyJavadocJar)
            pom {
                name.set("WorldWind Kotlin glTF Meshopt Codec")
                description.set("EXT_meshopt_compression decoder satellite for WorldWind Kotlin — JNI/cinterop binding around zeux/meshoptimizer. Registers with GltfDecoderRegistry on install.")
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

tasks.withType<AbstractPublishToMaven>().configureEach {
    val signingTasks = tasks.withType<Sign>()
    mustRunAfter(signingTasks)
}
