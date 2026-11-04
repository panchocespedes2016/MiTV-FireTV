// Offline build: every artifact (plugins + libraries) resolves from the
// local file repository in ./localrepo, mirrored by tools/mvnwalk.py.
// The build environment cannot reach public Maven repos from the Gradle
// daemon, so builds run with --offline against this repo only.
// The public repos below are fallbacks ONLY: they let a clean clone on
// a normal machine (./gradlew, with internet) resolve the same pinned
// versions without the mirror. localrepo stays first so the offline
// build keeps using the mirror and stays reproducible.
pluginManagement {
    repositories {
        maven { url = uri("localrepo") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("localrepo") }
        google()
        mavenCentral()
    }
}
rootProject.name = "MiTV-Firestick"
include(":app")
