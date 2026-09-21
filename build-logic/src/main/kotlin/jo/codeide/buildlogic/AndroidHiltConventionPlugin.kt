package jo.codeide.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Convention `codeide.android.hilt` — injection de dépendances Hilt via KSP.
 *
 * kapt est incompatible avec AGP 9 (voir ADR 0007) : Hilt fonctionne
 * exclusivement avec KSP. Applicable aux bibliothèques et à l'application.
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            dependencies.apply {
                add("implementation", libs.findLibrary("hilt-android").get())
                add("ksp", libs.findLibrary("hilt-compiler").get())
            }
        }
    }
}
