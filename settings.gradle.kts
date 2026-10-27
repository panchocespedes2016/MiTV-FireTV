// Offline build: every artifact (plugins + libraries) resolves from the
// local file repository in ./localrepo, mirrored by tools/mvnwalk.py.
// The build environment cannot reach public Maven repos from the Gradle
// daemon, so builds run with --offline against this repo only.
pluginManagement {
    repositories {
        maven { url = uri("localrepo") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("localrepo") }
    }
}
rootProject.name = "MiTV-Firestick"
include(":app")
