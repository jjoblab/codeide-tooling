package jo.codeide.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Convention `codeide.android.library` — bibliothèque Android commune.
 *
 * Concerne tous les modules `core:*` Android et (transitivement via
 * `codeide.android.feature`) les fonctionnalités. Le Kotlin est fourni par
 * le support intégré d'AGP 9 : aucun plugin `kotlin-android` à appliquer,
 * la configuration passe par l'extension `kotlin` (voir ADR 0007).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            pluginManager.apply("com.diffplug.spotless")
            pluginManager.apply("org.jetbrains.kotlinx.kover")

            extensions.configure<LibraryExtension> {
                namespace = namespaceDerive()

                compileSdk = catalogInt("compileSdk")
                compileSdkMinor = catalogInt("compileSdkMinor")

                defaultConfig {
                    minSdk = catalogInt("minSdk")
                }

                compileOptions {
                    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                    targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
                }

                testOptions {
                    unitTests {
                        // Manifeste et ressources fusionnés pour Robolectric.
                        isIncludeAndroidResources = true
                    }
                }

                // Règle 9 : Lint strict, sans ligne de base.
                //
                // Exceptions ciblées et commentées (autorisées par la
                // règle 9) : NewerVersionAvailable, GradleDependency et
                // AndroidGradlePluginVersion sont des conseils de
                // fraîcheur, pas des défauts. Les versions de Kotlin
                // (2.2.10) et kotlinx-serialization (1.9.0) sont
                // volontairement figées car dictées par le Kotlin intégré
                // d'AGP 9.4.1 (monter à 2.4.x / 1.11 casserait la lecture
                // des métadonnées compilées — ADR 0007) ; le wrapper Gradle
                // 9.7.1 est ÉPINGLÉ sur la Tooling API 9.7.1 (alignement
                // exact, docs/TOOLING.md — G4 : 9.8.0 passée stable le jour
                // même a fait apparaître le conseil, ce n'est pas un défaut
                // tant que l'alignement tooling est la règle).
                lint {
                    warningsAsErrors = true
                    abortOnError = true
                    disable.add("NewerVersionAvailable")
                    disable.add("GradleDependency")
                    disable.add("AndroidGradlePluginVersion")
                }
            }

            // Kotlin intégré AGP 9 : avertissements en erreur, jvmTarget = 17
            // (valeur par défaut alignée sur compileOptions.targetCompatibility).
            extensions.configure<KotlinAndroidProjectExtension>("kotlin") {
                compilerOptions {
                    allWarningsAsErrors.set(true)
                }
            }

            configurerDetekt()
            configurerSpotless()

            // Robolectric doit refléter les internes du JDK récent (JPMS) ;
            // voir docs/ENVIRONNEMENT.md et l'issue robolectric/robolectric#11434.
            // Tas borné : la machine CI n'a que 4 Go et les JVM de test
            // se succèdent séquentiellement (voir gradle.properties).
            tasks.withType<Test>().configureEach {
                jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
                maxHeapSize = "640m"
            }
        }
    }
}
