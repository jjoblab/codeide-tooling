package jo.codeide.buildlogic

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Convention `codeide.tooling.server` — l'orchestrateur du tooling Gradle
 * (G2, prompt compagnon Tooling, sections 2.1 et 4.7).
 *
 * Réunit les conventions d'une bibliothèque Kotlin JVM pure (via
 * `codeide.kotlin.library` : JVM 17, `explicitApi()`, detekt/spotless/kover)
 * et l'assemblage en **JAR unique exécutable** :
 *
 * - `shadowJar` produit `gradle-server.jar` ([GradleProtocol.SERVER_JAR_NAME]
 *   en dur, valeur littérale — le build-logic ne dépend pas du module
 *   protocol) avec `Merge-ServiceFiles` : les descripteurs de services de la
 *   Tooling API embarqués doivent survivre à la fusion ;
 * - `copierJarVersAssets` recopie ce JAR vers `app/src/main/assets/tooling/`
 *   (§4.7 : c'est de là que le daemon d'Android le déploiera) ;
 * - `controlerJarAssets` fait échouer le build si le JAR absent ou vide —
 *   elle est branchée sur `preBuild` de l'app : jamais d'APK sans JAR.
 *
 * Le JAR est un **artefact de build, jamais une source** : le répertoire
 * assets/tooling est ignoré par git et exclu des archives (package.sh),
 * chaque build le régénère.
 */
class ToolingServerConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("codeide.kotlin.library")
            pluginManager.apply("com.gradleup.shadow")

            val nomJar = "gradle-server.jar"
            // main() vit dans l'object ServerMain (@JvmStatic) — pas un
            // fichier ServerMain.kt top-level.
            val classePrincipale = "jo.codeide.tooling.server.ServerMain"

            tasks.withType<ShadowJar>().configureEach {
                archiveFileName.set(nomJar)
                archiveClassifier.set("all")
                archiveVersion.set("")
                manifest.attributes(mapOf("Main-Class" to classePrincipale))
                mergeServiceFiles()
            }

            val dossierAssets = rootProject.file("app/src/main/assets/tooling")

            val copier =
                tasks.register<Copy>("copierJarVersAssets") {
                    group = "build"
                    description = "Recopie le JAR orchestrateur vers les assets de l'app (§4.7 du prompt Tooling)."
                    from(tasks.withType<ShadowJar>())
                    into(dossierAssets)
                }

            tasks.register("controlerJarAssets") {
                group = "verification"
                description = "Contrôle §4.7 : le JAR orchestrateur est présent et non vide dans les assets."
                dependsOn(copier)
                val cible = dossierAssets.resolve(nomJar)
                doLast {
                    if (!cible.isFile) {
                        throw org.gradle.api.GradleException(
                            "JAR orchestrateur absent des assets : $cible (exécuter :tooling:server:copierJarVersAssets)",
                        )
                    }
                    if (cible.length() == 0L) {
                        throw org.gradle.api.GradleException("JAR orchestrateur vide dans les assets : $cible")
                    }
                }
            }
        }
    }
}
