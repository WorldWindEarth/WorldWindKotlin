pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.android") {
                useModule("com.android.tools.build:gradle:9.2.1")
            }
        }
    }
}
rootProject.name = "WorldWindKotlin"
include(":worldwind")
include(":worldwind-compose")
include(":worldwind-compose-samples")
include(":worldwind-compose-samples-android")
include(":worldwind-examples-android")
include(":worldwind-tutorials")
include(":worldwind-tutorials-android")
