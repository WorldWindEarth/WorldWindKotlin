pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.android") {
                useModule("com.android.tools.build:gradle:8.6.1")
            }
        }
    }
}
rootProject.name = "WorldWindKotlin"
include(":worldwind")
include(":worldwind-examples-android")
include(":worldwind-tutorials")
include(":worldwind-util-format")
include(":worldwind-util-glu")
include(":worldwind-util-kgl")
