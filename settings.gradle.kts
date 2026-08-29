pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven("https://libgdx.badlogicgames.com/nightlies/maven/")
    }
}

rootProject.name = "SpineWallpaper"
include(":app")
include(":spine-core-bridge")
include(":spine-runtime-v36")
include(":spine-runtime-v37")
include(":spine-runtime-v38")
include(":spine-runtime-v40")
include(":spine-runtime-v41")
include(":spine-runtime-v42")