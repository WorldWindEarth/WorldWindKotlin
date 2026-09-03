import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("dev.icerock.mobile.multiplatform-resources")
    id("maven-publish")
    id("signing")
}

// Resources only — deliberately no dependency on `:worldwind`, so the engine can depend on this
// module and reference its `MR` directly. The bundled imagery and geoid grid change far less often
// than the engine, so this module versions independently, the same arrangement the codec modules
// use with `worldwind.codecsVersion`. An engine release no longer re-uploads ~55 MB of unchanged
// binary assets across six targets; this artifact is published only when an asset changes.
version = providers.gradleProperty("worldwind.assetsVersion").get()

multiplatformResources {
    resourcesPackage.set("earth.worldwind.assets")
}

kotlin {
    applyDefaultHierarchyTemplate()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    js {
        browser()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain {
            dependencies {
                // The only dependency this module has: the generated `MR` is built on moko's
                // resource types. `api` so they stay visible to :worldwind, which reads them.
                api(libs.moko.resources)
            }
        }
    }
    @Suppress("UnstableApiUsage")
    android {
        namespace = "earth.worldwind.assets"
        compileSdk = providers.gradleProperty("worldwind.targetSdk").get().toInt()
        minSdk = providers.gradleProperty("worldwind.minSdk").get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

// Same duplication as :worldwind — moko-resources emits each resource both into the compilation
// output at `default/resources/moko-resources-js` and into `processedResources`, which the archive
// task packages at the klib root. Only the former is read when the klib is consumed from a
// repository. See the matching block in worldwind/build.gradle.kts for the full reasoning.
listOf("jsJar", "wasmJsJar").forEach { taskName ->
    tasks.named<Jar>(taskName) { exclude("images/**", "assets/**", "files/**") }
}

// Empty javadoc jar: Maven Central requires the artifact to exist, not to have content. This
// module is resources plus a handful of accessors, so there is nothing to document.
val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar") {
    archiveClassifier.set("javadoc")
    archiveAppendix.set("empty")
}

publishing {
    publications {
        withType<MavenPublication> {
            artifact(emptyJavadocJar)
            pom {
                name.set("WorldWind Kotlin Assets")
                description.set(
                    "Default imagery, star catalogue and EGM96 geoid grid for the WorldWind Kotlin SDK. " +
                        "Optional: the engine runs without it, falling back to no background image, " +
                        "no night lights, no stars and zero geoid offsets."
                )
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
    // Skip signing for local MavenLocal publishes (no GPG key configured). CI sets the env vars.
    if (!System.getenv("GPG_PRIVATE_KEY").isNullOrBlank()) sign(publishing.publications)
}

// Same non-macOS-host workaround as :worldwind — moko-resources' iOS pack action shells out to
// `xcrun` and writes a directory whose name embeds the klib's `<group>:<artifact>`, so it fails on
// Linux (no xcrun) and Windows (`:` is illegal in NTFS). See worldwind/build.gradle.kts for detail.
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

// https://github.com/gradle/gradle/issues/26091
tasks.withType<AbstractPublishToMaven>().configureEach {
    val signingTasks = tasks.withType<Sign>()
    mustRunAfter(signingTasks)
}
