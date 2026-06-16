import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("dev.icerock.mobile.multiplatform-resources")
}

multiplatformResources {
    resourcesPackage.set("${project.group}.tutorials")
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
    js {
        binaries.executable()
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
                // Pin the dev-server port so the browser origin stays stable across runs. The web
                // cache is IndexedDB, which the browser partitions by origin (scheme+host+PORT); if
                // webpack falls back 8080 -> 8081 the origin changes and the whole cache is lost.
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).copy(port = 8080)
            }
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        binaries.executable()
        // Target-level opt-in so it also covers the shared webMain source set.
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
                // Distinct fixed port from the js target so the two dev servers never collide and
                // each keeps a stable origin (and thus a persistent IndexedDB cache) across runs.
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).copy(port = 8081)
            }
        }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            // The SwiftUI tutorials app embeds this static framework. Apps `import WorldWindTutorials`
            // and call into the registry / WorldWindow factory from Swift.
            baseName = "WorldWindTutorials"
            isStatic = true
            // Re-export :worldwind so its types (WorldWindow, WorldWind engine, ...) appear in
            // the Obj-C/Swift header with their natural names. Without this, Swift sees them
            // as UIView*/WorldwindWorldWind etc., and code like `MainKt.createWorldWindow()`
            // (declared as returning earth.worldwind.WorldWindow) returns plain UIView.
            export(project(":worldwind"))
        }
    }
    @Suppress("UnstableApiUsage")
    android {
        namespace = "${project.group}.tutorials"
        compileSdk = providers.gradleProperty("worldwind.targetSdk").get().toInt()
        minSdk = providers.gradleProperty("worldwind.minSdk").get().toInt()

        enableCoreLibraryDesugaring = true

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        androidResources {
            enable = true
        }
    }
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("nonIos") {
                group("web") {
                    withJs()
                    withWasmJs()
                }
                group("jvmCommon") {
                    withJvm()
                    withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
                }
            }
        }
    }
    sourceSets {
        commonMain {
            // Generated TutorialApiKeys.kt (build/ is gitignored, so it never gets committed).
            kotlin.srcDir(layout.buildDirectory.dir("generated/source/apiKeys/commonMain/kotlin"))
            dependencies {
                implementation(project(":worldwind"))
                implementation(project(":worldwind-formats-gltf-draco"))
                implementation(project(":worldwind-formats-gltf-ktx2"))
            }
        }
        androidMain {
            dependencies {
                implementation(libs.androidx.appcompat)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.material)
            }
        }
        jvmMain {
            dependencies {
                // Make Ktor's OkHttp engine + OkHttpClient types visible to the tutorial-only
                // permissive-SSL hook (`installPermissiveSslForTutorials`). The engine module
                // already pulls these in for its own HTTP client, but `implementation`-scoped
                // there so they don't leak transitively.
                implementation(libs.ktor.client.okhttp)
                // VLCJ — uses the host's installed VLC 3.0+ libraries (no bundled natives).
                implementation(libs.vlcj)
                // JavaCV / FFmpeg via javacpp-presets. Pulls FFmpeg native binaries for the
                // current platform automatically. Used by both JavaCv* (FFmpegFrameGrabber)
                // and FFmpeg* (raw avformat/avcodec/swscale) tutorials.
                implementation(libs.javacv.platform)
                // JavaFX Media — bundles its own gstreamer-based decoder. Used by
                // JavaFxVideoTexture. The runtime JARs are platform-classified, and the
                // version-catalog provider syntax doesn't compose with `{ artifact { ... }}`,
                // so we expand the GAV to a string and append the host classifier.
                val javafxVersion = libs.versions.javafx.get()
                val javafxPlatform = when {
                    System.getProperty("os.name").lowercase().contains("win") -> "win"
                    System.getProperty("os.name").lowercase().contains("mac") -> "mac"
                    else -> "linux"
                }
                implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-media:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
            }
        }
        getByName("webMain") {
            // Source-set opt-in so the shared-metadata compile of webMain is covered too.
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                implementation(libs.kotlinx.browser)
                // Sync gzip for SPZ tiles (browser has no built-in sync gunzip).
                implementation(npm("pako", "~2.1.0"))
            }
        }
        jsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
        getByName("iosMain") {
            dependencies {
                api(project(":worldwind"))
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

// Writes TutorialApiKeys.kt from CESIUM_ION_TOKEN / GOOGLE_MAPS_API_KEY env (CI injects
// from GitHub Secrets; empty → tutorials use placeholder fallback). Keys ship in the
// public Pages bundle, so lock at provider: Google by HTTP referrer + Map Tiles API only;
// Cesium Ion by asset scope + assets:read only. Rotate per release.
val generateTutorialApiKeys by tasks.registering {
    description = "Write TutorialApiKeys.kt from CESIUM_ION_TOKEN / GOOGLE_MAPS_API_KEY env vars."
    val cesium = providers.environmentVariable("CESIUM_ION_TOKEN").orElse("")
    val google = providers.environmentVariable("GOOGLE_MAPS_API_KEY").orElse("")
    val outDir = layout.buildDirectory.dir("generated/source/apiKeys/commonMain/kotlin")
    inputs.property("cesium", cesium)
    inputs.property("google", google)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().asFile.resolve("earth/worldwind/tutorials/TutorialApiKeys.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package earth.worldwind.tutorials

            /** Build-time API keys; empty → tutorial falls back to placeholder. */
            internal object TutorialApiKeys {
                const val CESIUM_ION = "${cesium.get()}"
                const val GOOGLE_MAPS = "${google.get()}"
            }
            """.trimIndent() + "\n"
        )
    }
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java).configureEach {
    dependsOn(generateTutorialApiKeys)
}

// Mirror :worldwind's non-mac-host workaround: moko-resources packs into a klib subdir
// whose name is the klib unique_name (`<group>:<artifact>`) — `:` is illegal in NTFS, and
// Linux CI lacks `xcrun` (which the action shells out to). Strip on every non-macOS host.
val isMacHost = System.getProperty("os.name").lowercase().contains("mac")
afterEvaluate {
    if (isMacHost) return@afterEvaluate
    val unwrap: (Any) -> String = { wrapper ->
        var current: Any = wrapper
        var name = current.javaClass.name
        repeat(4) {
            val field = current.javaClass.declaredFields
                .firstOrNull { it.name in listOf("action", "delegate", "originalAction") }
            if (field != null) {
                field.isAccessible = true
                val next = field.get(current)
                if (next != null) {
                    current = next
                    name = current.javaClass.name
                }
            }
        }
        name
    }
    tasks.matching { it.name.startsWith("compileKotlinIos") }.configureEach {
        val task = this
        val before = task.actions.size
        task.actions.removeAll { unwrap(it).contains("PackAppleResources") }
        val removed = before - task.actions.size
        if (removed > 0) {
            logger.lifecycle("[ios-pack-strip] dropped $removed moko-resources action(s) on ${task.name}")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
}

// ---------------------------------------------------------------------------
// JVM-tutorials run task with JavaFX on the module path.
//
// Since JDK 9, JavaFX rejects its own classes when they're loaded from the unnamed module
// (i.e. plain classpath) — `Platform.startup(Runnable)` throws:
//   "Unsupported JavaFX configuration: classes were loaded from 'unnamed module @...'"
// The fix is to load `javafx-*.jar` from the *module path* via `--module-path` and pull
// the modules in with `--add-modules`. We can't apply the `application` plugin here (it
// conflicts with `com.android.application`), and the `org.openjfx.javafxplugin` Gradle
// plugin requires it — so we wire a plain `JavaExec` task that does the same thing.
//
// Run from the CLI: `./gradlew :worldwind-tutorials:runJvmTutorials`
// To run from IntelliJ's green Play button on `BasicGlobeTutorial.main`, copy the
// `--module-path ... --add-modules ...` args this task computes into the run config's
// "VM options" field.
// ---------------------------------------------------------------------------
tasks.register<JavaExec>("runJvmTutorials") {
    group = "application"
    description = "Run the JVM Swing tutorials with JavaFX configured on the module path."
    mainClass.set("earth.worldwind.tutorials.BasicGlobeTutorialKt")
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = mainCompilation.runtimeDependencyFiles + mainCompilation.output.allOutputs
    dependsOn(mainCompilation.compileTaskProvider)

    doFirst {
        // Pull JavaFX JARs out of the resolved runtime classpath and route them onto the
        // module path. Anything else (Kotlin stdlib, VLCJ, JavaCV, …) stays on the
        // classpath where it belongs. `File.pathSeparator` is `;` on Windows, `:` else.
        val javafxJars = classpath.files.filter {
            it.name.startsWith("javafx-") && it.name.endsWith(".jar")
        }
        if (javafxJars.isNotEmpty()) {
            jvmArgs("--module-path", javafxJars.joinToString(File.pathSeparator))
            jvmArgs(
                "--add-modules",
                "javafx.base,javafx.graphics,javafx.media,javafx.swing,javafx.controls"
            )
        }
    }
}
