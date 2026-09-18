pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ReadersPodcasts"
include(":app")

// Speech on the phone (whisper.cpp, llama.cpp, the models), shared with the sibling apps
// as a git submodule: the transcription and the translation both come from there.
include(":speech")
