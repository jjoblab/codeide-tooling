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
 * la version lue dans `version.properties` (source unique, section 9.1),
 * la configuration release avec minification (section 6 du prompt) et
 * la politique de signature de l'ADR 0067 (identité debug publique
 * versionnée + échelle release hors dépôt).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            pluginManager.apply("com.diffplug.spotless")
            pluginManager.apply("org.jetbrains.kotlinx.kover")

            val (nomVersion, codeVersion) = lireVersion()
            val signatureRelease = parametresSignatureRelease()

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

                // Politique de signature (ADR 0067). DEBUG : keystore
                // PUBLIC versionné dans config/signature/ — le runner GitHub
                // n'embarque aucun ~/.android/debug.keystore et les caches
                // actions/cache sont scopés par ref (runs de tags lancés en
                // parallèle) et évictables (7 jours) : chaque APK CI portait
                // une signature différente et Android refusait la mise à jour
                // (« conflit de package », retours v0.35.2 puis v0.37.1).
                // Versionner la clé debug — identifiants publics, patrons
                // AOSP/CodeAssist — fixe l'identité pour la CI, les
                // contributeurs et toute machine locale : les APK successifs
                // se mettent à jour les uns sur les autres, partout.
                val fichierCleDebug = rootDir.resolve("config/signature/debug.keystore")
                check(fichierCleDebug.isFile) {
                    "Keystore debug public manquant : ${fichierCleDebug.absolutePath} (ADR 0067) — " +
                        "restituer le fichier depuis le dépôt."
                }
                signingConfigs {
                    getByName("debug") {
                        storeFile = fichierCleDebug
                        storePassword = "android"
                        keyAlias = "androiddebugkey"
                        keyPassword = "android"
                    }
                    signatureRelease?.let { signature ->
                        create("release") {
                            storeFile = signature.fichierMagasin
                            storePassword = signature.motDePasseMagasin
                            keyAlias = signature.alias
                            keyPassword = signature.motDePasseCle
                        }
                    }
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
                        // ADR 0067 : signée par l'échelle hors dépôt quand un
                        // keystore release est résolu, sinon NON signée
                        // (comportement inchangé — la clé release ne vit
                        // jamais dans le dépôt).
                        signingConfig = signingConfigs.findByName("release")
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
