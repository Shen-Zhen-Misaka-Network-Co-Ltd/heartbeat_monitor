pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "heartwith"
include(":heartwith-compose")
project(":heartwith-compose").projectDir = file("clients/heartwith-compose")
include(":heartwith-web")
project(":heartwith-web").projectDir = file("clients/heartwith-web")
include(":heartwith-mihealth-lsp")
project(":heartwith-mihealth-lsp").projectDir = file("clients/heartwith-mihealth-lsp")
include(":xposed-api-stub")
project(":xposed-api-stub").projectDir = file("clients/xposed-api-stub")
