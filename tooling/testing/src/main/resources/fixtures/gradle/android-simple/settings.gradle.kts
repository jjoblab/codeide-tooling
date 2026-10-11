// Fixture ANDROID (mission « LSP Java » étape 1 — ADR 0113) : projet AGP
// réel à deux modules pour éprouver la source du classpath v2
// (BasicAndroidProject / VariantDependencies paramétré) — le cas que le
// fixture multi-module (plugins { java } pur JVM) n'a jamais couvert.
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
        mavenCentral()
    }
}
rootProject.name = "android-simple"
include(":app", ":lib")
