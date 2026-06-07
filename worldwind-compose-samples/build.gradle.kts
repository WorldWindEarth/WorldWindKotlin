import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("dev.icerock.mobile.multiplatform-resources")
    id("org.jetbrains.compose")
}

multiplatformResources {
    resourcesPackage.set("${project.group}.compose.samples")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    js {
        binaries.executable()
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
                // Pin the dev-server port so the browser origin (and its IndexedDB cache) stays
                // stable across runs; otherwise webpack falls back to another port, the origin
                // changes, and the origin-scoped cache is lost. Distinct from the tutorials' ports.
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).copy(port = 8082)
            }
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        binaries.executable()
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
                // Distinct fixed port from the js target (and the tutorials) so dev servers never
                // collide and each keeps a stable origin / persistent IndexedDB cache across runs.
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).copy(port = 8083)
            }
        }
    }
    // iosX64 (Intel-Mac simulator) intentionally dropped — Compose Multiplatform stopped
    // publishing the iosX64 variant. Core :worldwind still targets iosX64 for non-Compose
    // consumers; only the Compose binding requires Apple-Silicon simulators / devices.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            // Static framework: simpler embedding for SwiftUI/UIKit hosts (no need to add
            // a "Embed and Sign" copy phase; Xcode just links the .a). Compose Multiplatform
            // apps name this "ComposeApp" by convention so Swift code can `import ComposeApp`
            // and call `MainViewController()` from a UIViewControllerRepresentable.
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    @Suppress("UnstableApiUsage")
    android {
        namespace = "earth.worldwind.compose.samples"
        compileSdk = providers.gradleProperty("worldwind.targetSdk").get().toInt()
        minSdk = providers.gradleProperty("worldwind.minSdk").get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        androidResources {
            enable = true
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":worldwind-compose"))
            }
        }
        androidMain {
            dependencies {
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.androidx.activity.compose)
            }
        }
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        jsMain {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.html.core)
            }
        }
        wasmJsMain {
            dependencies {
                implementation(libs.compose.runtime)
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
                // compose.foundation + compose.ui pull in the Compose-iOS runtime, including
                // ComposeUIViewController which our MainViewController() helper wraps.
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

// Mirror the workaround from :worldwind: moko-resources packs into a klib subdirectory
// whose name is the klib unique_name (`<group>:<artifact>`), and `:` is illegal in NTFS
// filenames. Strip the doLast action on iOS compile tasks so the build runs on Windows.
// See `project_moko_resources_windows_ios` memory.
afterEvaluate {
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

tasks.register<JavaExec>("runJvmComposeSample") {
    group = "application"
    description = "Run the Compose Desktop sample app."
    mainClass.set("earth.worldwind.compose.samples.MainKt")
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = mainCompilation.runtimeDependencyFiles + mainCompilation.output.allOutputs
    dependsOn(mainCompilation.compileTaskProvider)
}
