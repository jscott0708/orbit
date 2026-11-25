pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Firefox's browser engine (GeckoView) comes from Mozilla's own repository
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}
rootProject.name = "Orbit"
include(":app")
