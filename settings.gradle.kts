pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "NDXInvest"

include(":ledger:domain")
include(":ledger:persistence")
include(":core:database")
include(":core:network")
include(":core:security")
include(":market:domain")
include(":data:providers")
include(":app")
include(":yjb:read")
