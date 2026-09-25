package jo.codeide.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Convention `codeide.android.application` — module d'application `app`.
 *
 * Porte l'identifiant imposé `jo.codeide` (règle : ne jamais le changer),
 * la version lue dans `version.properties` (source unique, section 9.1) et
 * la configuration release avec minification (section 6 du prompt).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            pluginManager.apply("com.diffplug.spotless")
            pluginManager.apply("org.jetbrains.kotlinx.kover")

            val (nomVersion, codeVersion) = lireVersion()

            extensions.configure<ApplicationExtension> {
                // Namespace racine de l'application (section 2 : jo.codeide).
                namespace = "jo.codeide"

                compileSdk = catalogInt("compileSdk")
                compileSdkMinor = catalogInt("compileSdkMinor")

                defaultConfig {
                    applicationId = "jo.codeide"
                    minSdk = catalogInt("minSdk")
                    targetSdk = catalogInt("targetSdk")
                    versionCode = codeVersion
                    versionName = nomVersion
                }

                buildTypes {
                    release {
                        // Section 6 : minification et réduction activées ;
                        // les règles R8 seront affinées à l'étape 13.
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro",
                        )
                    }
                }

                compileOptions {
                    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                    targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
                }

                testOptions {
                    unitTests {
                        isIncludeAndroidResources = true
                    }
                }

                // Règle 9 : Lint strict, sans ligne de base.
                // Exceptions ciblées pour les conseils de fraîcheur — voir la
                // justification complète dans AndroidLibraryConventionPlugin / ADR 0007
                // (ajout G4 : AndroidGradlePluginVersion — wrapper 9.7.1 épinglé
                // sur la Tooling API 9.7.1, docs/TOOLING.md ; ajout v0.31.1 :
                // ExpiringTargetSdkVersion — l'app est chargée par
                // side-loading, l'exigence Play ne s'applique pas, et le
                // targetSdk 28 est délibéré pour l'exécution des binaires du
                // bootstrap — ADR 0045 ; ajout v0.31.3 : la version ERREUR
                // ExpiredTargetSdkVersion — même décision, même ADR. Les DEUX
                // ID doivent être désactivés : Expiring est le conseil
                // (sévérité avertissement, monté en erreur par
                // warningsAsErrors), Expired est l'erreur directe — la
                // désactivation v0.31.1 n'a couvert que la première et la CI
                // échouait sur :app:lintDebug depuis).
                lint {
                    warningsAsErrors = true
                    abortOnError = true
                    disable.add("NewerVersionAvailable")
                    disable.add("GradleDependency")
                    disable.add("AndroidGradlePluginVersion")
                    disable.add("ExpiringTargetSdkVersion")
                    disable.add("ExpiredTargetSdkVersion")
                }
            }

            extensions.configure<KotlinAndroidProjectExtension>("kotlin") {
                compilerOptions {
                    allWarningsAsErrors.set(true)
                }
            }

            configurerDetekt()
            configurerSpotless()

            // Robolectric doit refléter les internes du JDK récent (JPMS) ;
            // voir docs/ENVIRONNEMENT.md. Tas borné pour la machine CI (4 Go).
            tasks.withType<Test>().configureEach {
                jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
                maxHeapSize = "640m"
            }
        }
    }
}
