pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.android") {
                useModule("com.android.tools.build:gradle:8.12.0")
            }
        }
    }
}
rootProject.name = "WorldWindKotlin"
include(":worldwind")
include(":worldwind-examples-android")
include(":worldwind-tutorials")
