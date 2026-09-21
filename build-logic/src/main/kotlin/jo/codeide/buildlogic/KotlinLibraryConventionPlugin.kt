package jo.codeide.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Convention `codeide.kotlin.library` — bibliothèque Kotlin JVM pure.
 *
 * Concerne `core:model` et `core:domain` (section 5.1) : modules rapides à
 * tester, sans dépendance Android. `explicitApi()` force la visibilité
 * publique explicite et la KDoc sur l'API exposée.
 */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            pluginManager.apply("com.diffplug.spotless")
            pluginManager.apply("org.jetbrains.kotlinx.kover")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
            }

            extensions.configure<KotlinJvmProjectExtension> {
                explicitApi()
                compilerOptions {
                    allWarningsAsErrors.set(true)
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }

            configurerDetekt()
            configurerSpotless()

            tasks.withType<Test>().configureEach {
                useJUnit()
                // Tas borné pour la machine CI (4 Go) — voir gradle.properties.
                maxHeapSize = "640m"
            }

            dependencies.add(
                "testImplementation",
                libs.findLibrary("junit4").get(),
            )
        }
    }
}
