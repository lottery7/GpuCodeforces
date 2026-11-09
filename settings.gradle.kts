pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "GpuCodeforces"
include(":backend")

// Testing system (see judge/README.md)
include(":judge-api", ":judge-gateway", ":judge-executor")
project(":judge-api").projectDir = file("judge/judge-api")
project(":judge-gateway").projectDir = file("judge/judge-gateway")
project(":judge-executor").projectDir = file("judge/judge-executor")
