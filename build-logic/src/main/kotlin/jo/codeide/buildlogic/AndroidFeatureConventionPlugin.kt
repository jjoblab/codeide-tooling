package jo.codeide.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention `codeide.android.feature` — module de fonctionnalité.
 *
 * Un `feature:*` est une bibliothèque Android avec ViewBinding activé,
 * Hilt et les dépendances d'interface autorisées par la section 5.2 :
 * `core:ui`, `core:domain`, `core:model` et les bibliothèques UI AndroidX.
 * Les fonctionnalités ne se connaissent jamais entre elles et n'accèdent
 * jamais aux sources de données (vérifié par `checkModuleDependencies`).
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("codeide.android.library")
            pluginManager.apply("codeide.android.hilt")

            extensions.configure<LibraryExtension> {
                buildFeatures {
                    viewBinding = true
                }
            }

            dependencies.apply {
                add("implementation", project(":core:ui"))
                add("implementation", project(":core:domain"))
                add("implementation", project(":core:model"))

                add("implementation", libs.findLibrary("androidx-fragment").get())
                add("implementation", libs.findLibrary("androidx-navigation-fragment").get())
                add("implementation", libs.findLibrary("androidx-navigation-ui").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-savedstate").get())
                add("implementation", libs.findLibrary("androidx-recyclerview").get())
                add("implementation", libs.findLibrary("androidx-material").get())
            }
        }
    }
}
