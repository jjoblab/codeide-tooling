package jo.codeide.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.register

/**
 * Convention `codeide.module-rules` — vérification automatique des règles de
 * dépendance entre modules (sections 3.2 et 5.2 du prompt maître).
 *
 * Appliquée au projet racine, elle enregistre la tâche
 * `checkModuleDependencies` qui fait échouer le build si un module dépend
 * d'un module interdit. Trois familles de règles :
 *
 * 1. liste d'autorisation par module (allow-list de la section 5.2) ;
 * 2. `core:testing` n'est consommable qu'en configuration de test ;
 * 3. `core:model` et `core:domain` sont des modules Kotlin JVM purs :
 *    aucun plugin Android ne peut leur être appliqué.
 */
class ModuleRulesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project.rootProject == project) {
            "codeide.module-rules doit être appliqué au projet racine uniquement"
        }

        val violationsCollectees: ListProperty<String> = project.objects.listProperty()

        // NB : le plugin kotlin-dsl active « sam-with-receiver » — les lambdas
        // Action<T> de Gradle s'écrivent avec `this` implicite, sans paramètre.
        project.subprojects {
            afterEvaluate { violationsCollectees.addAll(recenserViolations(this)) }
        }

        project.tasks.register<CheckModuleDependenciesTask>("checkModuleDependencies").configure {
            group = "verification"
            description = "Vérifie les règles de dépendance entre modules (section 5.2 du prompt maître)."
            violations.set(violationsCollectees)
            rapport.set(project.layout.buildDirectory.file("reports/module-dependencies.txt"))
        }
    }

    private companion object {
        /** Configurations dont les dépendances de module sont surveillées. */
        val CONFIGS_SURVEILLEES = setOf(
            "api",
            "implementation",
            "compileOnly",
            "ksp",
            "kspDebug",
            "kspRelease",
            "testImplementation",
            "testFixturesApi",
            "testFixturesImplementation",
            "androidTestImplementation",
            "kspTest",
            "kspAndroidTest",
        )

        /** Configurations dans lesquelles `core:testing` peut apparaître. */
        val CONFIGS_TEST = setOf(
            "testImplementation",
            "testFixturesApi",
            "testFixturesImplementation",
            "androidTestImplementation",
            "kspTest",
            "kspAndroidTest",
        )

        /** Modules Kotlin JVM purs (section 5.1). */
        val MODULES_PURS = setOf(":core:model", ":core:domain")

        /** Plugins Android interdits aux modules purs. */
        val PLUGINS_ANDROID = listOf("com.android.application", "com.android.library", "com.android.dynamic-feature")
    }

    /** Liste d'autorisation par module (tableau de la section 5.2). */
    private fun autorisations(chemin: String): Set<String>? = when {
        chemin == ":app" -> null // assemblage final : tout est permis (sauf core:testing hors test)
        chemin == ":core:model" -> emptySet()
        chemin == ":core:domain" -> setOf(":core:model")
        chemin in setOf(":core:database", ":core:datastore", ":core:storage", ":core:logging") ->
            setOf(":core:model", ":core:domain")
        chemin == ":core:crash" -> setOf(":core:model", ":core:domain", ":core:ui")
        chemin == ":core:data" ->
            setOf(":core:model", ":core:domain", ":core:database", ":core:datastore", ":core:storage", ":core:logging")
        chemin == ":core:ui" -> setOf(":core:model")
        chemin == ":core:testing" -> setOf(":core:model", ":core:domain")
        chemin.startsWith(":feature:") -> setOf(":core:ui", ":core:domain", ":core:model")
        else -> null // module non répertorié : pas de contrainte (extensibilité)
    }

    /** Recherche toutes les violations de règles d'un module. */
    private fun recenserViolations(module: Project): List<String> {
        val problems = mutableListOf<String>()
        val chemin = module.path
        val autorises = autorisations(chemin)

        if (chemin in MODULES_PURS) {
            val pluginsFautifs = PLUGINS_ANDROID.filter { module.pluginManager.hasPlugin(it) }
            if (pluginsFautifs.isNotEmpty()) {
                problems += "$chemin est un module Kotlin JVM pur mais applique : ${pluginsFautifs.joinToString()}"
            }
        }

        for (configuration in module.configurations) {
            if (configuration.name !in CONFIGS_SURVEILLEES) continue
            for (dependance in configuration.dependencies) {
                if (dependance is ProjectDependency) {
                    // API Gradle 9 : ProjectDependency.getPath() renvoie le
                    // chemin du projet cible (ex. « :core:model »).
                    val cible = dependance.path
                    if (cible == chemin) continue

                    if (cible == ":core:testing") {
                        // `core:testing` est consommable par les tests de
                        // n'importe quel module (section 5.2 : « utilisé en
                        // testImplementation seulement ») : la liste
                        // d'autorisation décrit les dépendances de
                        // production, pas les doubles de test.
                        if (configuration.name !in CONFIGS_TEST) {
                            problems += "$chemin utilise :core:testing dans '${configuration.name}' " +
                                "(réservé aux configurations de test)"
                        }
                        continue
                    }

                    if (autorises != null && cible !in autorises) {
                        problems += "$chemin dépend de $cible (interdit par la section 5.2 " +
                            "— autorisés ici : ${if (autorises.isEmpty()) "aucun" else autorises.joinToString()})"
                    }
                }
            }
        }
        return problems
    }
}

/** Tâche qui fait échouer le build si les règles de dépendance sont violées. */
abstract class CheckModuleDependenciesTask : DefaultTask() {

    /** Violations collectées à la configuration ; vide si tout est conforme. */
    @get:Input
    abstract val violations: ListProperty<String>

    /** Emplacement du rapport texte. */
    @get:OutputFile
    abstract val rapport: RegularFileProperty

    @TaskAction
    fun verifier() {
        val trouvees = violations.get()
        val fichier = rapport.get().asFile
        fichier.parentFile.mkdirs()
        fichier.writeText(
            if (trouvees.isEmpty()) {
                "Conforme : aucune violation des règles de dépendance entre modules.\n"
            } else {
                trouvees.joinToString(separator = "\n")
            },
        )
        if (trouvees.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Règles de dépendance entre modules violées (${trouvees.size}) :")
                    trouvees.forEach { appendLine(" - $it") }
                    appendLine("Voir docs/ARCHITECTURE.md, section « Règles de dépendance ».")
                },
            )
        }
        logger.lifecycle("checkModuleDependencies : conforme (rapport : ${fichier.absolutePath})")
    }
}
