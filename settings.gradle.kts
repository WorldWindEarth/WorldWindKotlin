pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.android") {
                useModule("com.android.tools.build:gradle:7.4.2")
            }
        }
    }
}
rootProject.name = "WorldWindKotlin"
include(":worldwind")
include(":worldwind-examples-android")
include(":worldwind-tutorials")
