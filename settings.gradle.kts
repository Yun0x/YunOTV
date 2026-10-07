pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "YunoTV"
include(":app", ":core", ":core-model", ":core-network", ":core-config", ":core-database", ":core-player", ":core-ui",
    ":feature-home", ":feature-search", ":feature-detail", ":feature-player", ":feature-history", ":feature-favorite", ":feature-settings", ":feature-source", ":feature-live")

include(":core-runtime", ":qa-release")
