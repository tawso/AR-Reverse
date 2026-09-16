pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ARKotlinReserve"
include(":ar-core", ":ar-tracking-opencv", ":ar-renderer", ":arkotlinreserve", ":sample-app")
