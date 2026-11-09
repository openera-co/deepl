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
        // Google's mirror of Maven Central first for Robolectric's very large runtime jars (avoids 429s).
        maven("https://maven-central.storage-download.googleapis.com/maven2") { content { includeGroup("org.robolectric") } }
        mavenCentral()
    }
}
rootProject.name = "Carnet"
include(":core", ":app")
// Native engines (llama.cpp / whisper.cpp) download and compile third-party C++ at build time.
// Enable with -Pcarnet.native=true (the CI workflow does); without them the app still builds and
// the optional Improve/handwriting features report themselves as unavailable.
val nativeEngines = providers.gradleProperty("carnet.native").orNull == "true"
if (nativeEngines) include(":native-llama", ":native-whisper")
