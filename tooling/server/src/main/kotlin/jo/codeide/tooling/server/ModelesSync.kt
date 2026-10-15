package jo.codeide.tooling.server

import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.GradleTask
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.io.File
import java.io.Serializable

/**
 * Résolution des modèles du tooling (v4 §3.1 + v8 ADR 0113) : TÂCHES et
 * modules ANDROID (modèle v2 AGP) dans UNE action Tooling API, modules
 * JVM purs (IdeaProject) par le MODEL BUILDER — la partition n'est pas un
 * caprice :
 *
 * - l'action unique évitait (v4) que la double suite de `model().get()`
 *   ne reconfigure le build deux fois — les TÂCHES et les modèles v2
 *   Android n'ont pas d'équivalent model-builder paramétrable, ils
 *   restent dans l'action ;
 * - IdeaProject par contre PERD ses dépendances typées dans une action :
 *   l'adaptateur côté démon ne proxifie `DomainObjectSet<IdeaDependency>`
 *   que par l'interface de BASE — `IdeaModuleDependency` (donc
 *   `targetModuleName`) devient invisible au `instanceof` (mesuré :
 *   `DefaultIdeaModuleDependency{targetModuleName='lib'}` derrière un
 *   proxy `IdeaDependency` nu). Le model builder, lui, livre les types
 *   concrets (prouvé par le banc). Le modèle idea est donc résolu par le
 *   model builder APRÈS l'action — le démon est déjà configuré, l'aller
 *   est peu coûteux ;
 * - la FUSION privilégie le modèle v2 par chemin de projet (IdeaProject
 *   est VIDE pour les modules Android — AGP ne publie pas ses
 *   configurations dans le modèle idea, constat §2.1 de l'étude).
 *
 * Les DTO sont `java.io.Serializable` (sérialisation du Tooling API),
 * puis traduits en messages du protocole côté orchestrateur
 * ([versClasspathModule]) — les types du protocole (kotlinx.serialization)
 * ne traversent PAS la frontière Tooling API.
 */
internal class ActionSyncModeles(
    private val marquerPhases: Boolean = true,
    private val avecTaches: Boolean = true,
) : BuildAction<ResultatActionModeles> {
    override fun execute(controleur: BuildController): ResultatActionModeles {
        val taches =
            if (avecTaches) {
                if (marquerPhases) {
                    controleur.send(MarqueurPhaseModele.MODELE_TACHES)
                }
                extraireTaches(controleur.getModel(GradleProject::class.java))
            } else {
                emptyList()
            }

        if (marquerPhases) {
            // La phase MODELE_IDE couvre la collecte Android v2 (dans
            // l'action) ET la résolution IdeaProject (model builder, juste
            // après — cf. KDoc de la classe).
            controleur.send(MarqueurPhaseModele.MODELE_IDE)
        }
        val modulesAndroid = ModelesAndroidV2.collecter(controleur)
        return ResultatActionModeles(taches = taches, modulesAndroid = modulesAndroid)
    }

    /** Tâches de l'arbre (racine d'abord, sous-projets ensuite). */
    private fun extraireTaches(racine: GradleProject): List<TacheDto> {
        val collectees = mutableListOf<TacheDto>()
        parcourirTaches(racine, collectees)
        return collectees
    }

    private fun parcourirTaches(
        projet: GradleProject,
        collectees: MutableList<TacheDto>,
    ) {
        projet.tasks.all.forEach { tache: GradleTask ->
            collectees += TacheDto(path = tache.path, group = tache.group, displayName = tache.displayName)
        }
        projet.children.all.forEach { enfant -> parcourirTaches(enfant, collectees) }
    }
}

/**
 * Résultat de l'ACTION (tâches + modules Android v2) — traverse la
 * Tooling API.
 */
internal data class ResultatActionModeles(
    val taches: List<TacheDto>,
    val modulesAndroid: List<ModuleClasspathDto>,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Résultat ORCHESTRATEUR complet (tâches + modules fusionnés) — déposé
 * dans le [CacheSync] par la sync.
 */
internal data class ResultatModelesSync(
    val taches: List<TacheDto>,
    val modules: List<ModuleClasspathDto>,
)

/**
 * Modules JVM purs d'un modèle IdeaProject résolu par MODEL BUILDER (voir
 * le KDoc de [ActionSyncModeles] — jamais dans une action).
 */
internal fun IdeaProject.versModulesDto(): List<ModuleClasspathDto> {
    // Identité canonique (v8) : nom IdeaModule → chemin Gradle — les
    // entrées MODULE désignent leur cible par le MÊME identifiant que
    // ClasspathModule.name (chemin, ex. `:lib`).
    val cheminsParNom = modules.all.associate { module -> module.name to module.cheminGradle() }
    return modules.all.map { it.versClasspathDto(cheminsParNom) }
}

/**
 * Fusionne les DEUX sources : un module Android (présent dans
 * [modulesAndroid] par son chemin) remplace son homologue IdeaProject
 * (vide pour lui — AGP ne publie pas ses configurations dans le modèle
 * idea) ; un module JVM pur garde sa conversion IdeaProject. Les modules
 * sans l'un ni l'autre (conteneurs sans sources ni entrées) sont marqués
 * `ignore` avec leur raison — écartés avec raison conservée (§7.1 du
 * prompt 2).
 */
internal fun fusionner(
    modulesIdea: List<ModuleClasspathDto>,
    modulesAndroid: List<ModuleClasspathDto>,
): List<ModuleClasspathDto> {
    val androidParChemin = modulesAndroid.associateBy { it.name }
    val fusionnes = modulesIdea.map { androidParChemin[it.name] ?: it }.toMutableList()
    val cheminsIdea = modulesIdea.map { it.name }.toSet()
    fusionnes += modulesAndroid.filter { it.name !in cheminsIdea }
    return fusionnes.map { module ->
        if (estConteneur(module)) {
            module.copy(ignore = true, raisonIgnore = "module conteneur sans sources ni classpath")
        } else {
            module
        }
    }
}

/**
 * Résout les modules JVM purs d'une connexion par le MODEL BUILDER (le
 * démon est déjà configuré par l'action qui précède — l'aller reste peu
 * coûteux). Les arguments réglés s'appliquent comme à l'action.
 */
internal fun ProjectConnection.modelesIdea(arguments: List<String>): List<ModuleClasspathDto> =
    model(IdeaProject::class.java)
        .withArguments(arguments)
        .get()
        .versModulesDto()

/** Un module sans sources, sans classpath et sans plateforme : conteneur. */
private fun estConteneur(module: ModuleClasspathDto): Boolean =
    module.sourceDirs.isEmpty() &&
        module.sourceDirsTest.isEmpty() &&
        module.entries.isEmpty() &&
        module.androidJar == null

/**
 * Marqueur de transition de phase streamé par [ActionSyncModeles] (v4) :
 * l'action annonce le modèle qu'elle S'APPRÊTE à résoudre — le client de
 * l'action (le [SyncHandler]) en fait des `SyncProgress` départ/fin avec
 * durées mesurées entre marqueurs.
 */
internal enum class MarqueurPhaseModele {
    MODELE_TACHES,
    MODELE_IDE,
}

/** Tâche extraite du modèle (miroir sérialisable de `TaskInfo`). */
internal data class TacheDto(
    val path: String,
    val group: String?,
    val displayName: String,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Module de classpath extrait (miroir sérialisable de
 * [jo.codeide.tooling.protocol.ClasspathModule]).
 *
 * v8 (étape 1, ADR 0113) : [name] est le CHEMIN Gradle (ex. `:app`) pour
 * les DEUX sources — les entrées MODULE (path = chemin du module frère)
 * et les noms de modules se correspondent ; [sourceDirs] ne porte que les
 * racines PRINCIPALES, [sourceDirsTest] les racines de tests.
 *
 * v10 (S1-a) : les niveaux Java du projet ([sourceCompatibility] /
 * [targetCompatibility], ex. « 1.8 », « 17 », « 21 ») voyagent avec le
 * module — `null` = indécidable (le LSP garde son défaut « 17 »).
 */
internal data class ModuleClasspathDto(
    val name: String,
    val sourceDirs: List<String>,
    val entries: List<EntreeClasspathDto>,
    val sourceDirsTest: List<String> = emptyList(),
    val varianteAndroid: String? = null,
    val fichiersGeneres: List<String> = emptyList(),
    val androidJar: String? = null,
    val ignore: Boolean = false,
    val raisonIgnore: String? = null,
    val avertissements: List<String> = emptyList(),
    val sourceCompatibility: String? = null,
    val targetCompatibility: String? = null,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 3L
    }
}

/** Entrée de classpath extraite (miroir de `ClasspathEntry`). */
internal data class EntreeClasspathDto(
    val path: String,
    val kind: String,
    val scope: String?,
    val sources: String?,
    val aar: String? = null,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 2L
    }
}

/**
 * Chemin Gradle d'un module Idea (v8) — identifiant canonique aligné sur
 * le modèle v2 AGP et les entrées MODULE. Repli sur le nom si un Gradle
 * très ancien ne l'expose pas (le serveur n'a pas d'AppLogger, règle 14
 * — un identifiant imparfait vaut mieux qu'un échec).
 */
@Suppress("SwallowedException")
internal fun IdeaModule.cheminGradle(): String =
    try {
        gradleProject.path
    } catch (e: org.gradle.tooling.model.UnsupportedMethodException) {
        name
    }

/**
 * Traduction d'un module Idea en classpath LSP (ADR 0058, v8) :
 * répertoires sources PRINCIPAUX d'un côté, de TEST de l'autre, jars,
 * AARs, dossiers de classes, modules frères par leur CHEMIN, jar de
 * sources attaché quand Gradle le connaît. Les dépendances sans fichier
 * localisable sont ignorées silencieusement : seules les entrées
 * LOCALISABLES comptent pour un LSP.
 *
 * `name` = chemin Gradle du module — aligné sur les chemins du modèle v2
 * AGP et des entrées MODULE.
 */
internal fun IdeaModule.versClasspathDto(cheminsParNom: Map<String, String>): ModuleClasspathDto {
    val chemin = cheminGradle()
    val sources =
        contentRoots.all
            .flatMap { racine -> racine.sourceDirectories.all.map { it.directory } }
            .map { it.absolutePath }
            .distinct()
    val sourcesTest =
        contentRoots.all
            .flatMap { racine -> racine.testDirectories.all.map { it.directory } }
            .map { it.absolutePath }
            .distinct()
    val entrees =
        dependencies.all
            .mapNotNull { dependance ->
                when (dependance) {
                    is IdeaModuleDependency -> {
                        // v8 : le chemin du module frère (pas son nom Idea)
                        // — les entrées MODULE désignent le module par le
                        // MÊME identifiant que ClasspathModule.name.
                        EntreeClasspathDto(
                            path = cheminsParNom[dependance.targetModuleName] ?: dependance.targetModuleName,
                            kind = "MODULE",
                            scope = dependance.scope.scope,
                            sources = null,
                        )
                    }

                    is IdeaSingleEntryLibraryDependency -> {
                        EntreeClasspathDto(
                            path = dependance.file.path,
                            kind = natureEntree(dependance.file),
                            scope = dependance.scope.scope,
                            sources = dependance.source?.path,
                        )
                    }

                    else -> {
                        null
                    }
                }
            }.distinct()
    // v10 (S1-a) : niveaux Java du module JVM pur — languageLevel
    // (sourceCompatibility) et targetBytecodeVersion (targetCompatibility)
    // de `javaLanguageSettings`. JavaVersion.toString() livre « 1.8 »,
    // « 11 », « 17 », « 21 » — la notation historique est la CONVENTION du
    // protocole (le moteur LSP normalise « 1.x » → « x »). Résolution
    // protégée : un Gradle ancien (UnsupportedMethodException) ou un modèle
    // absent laissent `null` — le repli défaut « 17 » est total.
    val niveaux = niveauxJavaIdea()
    return ModuleClasspathDto(
        name = chemin,
        sourceDirs = sources,
        sourceDirsTest = sourcesTest,
        entries = entrees,
        sourceCompatibility = niveaux.first,
        targetCompatibility = niveaux.second,
    )
}

/**
 * Niveaux Java (source, cible) d'un module Idea — `null` de chaque côté
 * si le modèle ne les expose pas (Gradle ancien, module sans Java).
 * Aucune exception ne remonte : le classpath est plus important que ses
 * métadonnées (règle 14). Expression unique : AUCUN retour anticipé
 * (detekt ReturnCount).
 */
@Suppress("TooGenericExceptionCaught")
private fun IdeaModule.niveauxJavaIdea(): Pair<String?, String?> =
    runCatching {
        val reglages = javaLanguageSettings
        if (reglages == null) {
            null to null
        } else {
            runCatching { reglages.languageLevel?.toString() to reglages.targetBytecodeVersion?.toString() }
                .getOrElse { null to null }
        }
    }.getOrElse { null to null }

/** Nature d'une entrée de classpath selon son fichier (jar, aar, dossier). */
private fun natureEntree(fichier: File): String =
    when {
        fichier.isDirectory -> "DOSSIER"
        fichier.extension.equals(other = "aar", ignoreCase = true) -> "AAR"
        else -> "JAR"
    }
