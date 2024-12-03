plugins {
    kotlin("multiplatform")
    id("worldwind.maven-publish")
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
        moduleName = project.name
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
    }
    sourceSets {
       commonMain {
           dependencies {
               api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
           }
       }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
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
