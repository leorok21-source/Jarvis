pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "JARVIS"
include(":app")

include(":llamaLib")
project(":llamaLib").projectDir = file("llama.cpp/examples/llama.android/lib")
