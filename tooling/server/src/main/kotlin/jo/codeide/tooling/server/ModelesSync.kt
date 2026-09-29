package jo.codeide.tooling.server

import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.GradleTask
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.io.File
import java.io.Serializable

/**
 * Action UNIQUE de synchronisation (v4, §3.1) : résout [GradleProject] puis
 * [IdeaProject] **dans une seule requête Tooling API** — l'ancienne double
 * suite de `model().get()` configurait le build DEUX fois. Le résultat
 * embarque les tâches ET les classpaths extraits (le listage et le
 * classpath LSP répondent ensuite depuis le [CacheSync] sans re-résoudre).
 *
 * Les transitions internes (fin du modèle tâches, début du modèle IDE)
 * voyagent par [BuildController.send] vers le
 * `StreamedValueListener` de l'exécuteur : l'action s'exécute DANS le
 * daemon, elle ne peut toucher ni le bus ni un lambda client — seules des
 * valeurs sérialisables traversent (marqueurs [MarqueurPhaseModele]).
 *
 * Les DTO sont `java.io.Serializable` (sérialisation du Tooling API), puis
 * traduits en messages du protocole côté orchestrateur — les types du
 * protocole (kotlinx.serialization) ne traversent PAS la frontière
 * Tooling API.
 */
internal class ActionSyncModeles : BuildAction<ResultatModelesSync> {
    override fun execute(controleur: BuildController): ResultatModelesSync {
        controleur.send(MarqueurPhaseModele.MODELE_TACHES)
        val taches = extraireTaches(controleur.getModel(GradleProject::class.java))

        controleur.send(MarqueurPhaseModele.MODELE_IDE)
        val modules = extraireModules(controleur.getModel(IdeaProject::class.java))

        return ResultatModelesSync(taches = taches, modules = modules)
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

    /** Classpath LSP de chaque module (sources + entrées localisables). */
    private fun extraireModules(idea: IdeaProject): List<ModuleClasspathDto> =
        idea.modules.all.map { it.versClasspathDto() }
}

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

/** Résultat sérialisable de l'action unique (traverse la Tooling API). */
internal data class ResultatModelesSync(
    val taches: List<TacheDto>,
    val modules: List<ModuleClasspathDto>,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
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

/** Module de classpath extrait (miroir sérialisable de `ClasspathModule`). */
internal data class ModuleClasspathDto(
    val name: String,
    val sourceDirs: List<String>,
    val entries: List<EntreeClasspathDto>,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Entrée de classpath extraite (miroir de `ClasspathEntry`). */
internal data class EntreeClasspathDto(
    val path: String,
    val kind: String,
    val scope: String?,
    val sources: String?,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Traduction d'un module Idea en classpath LSP — IDENTIQUE à celle du
 * [ClasspathHandler] historique (ADR 0058) : répertoires sources (main +
 * tests), jars, AARs, dossiers de classes, modules frères par leur nom,
 * jar de sources attaché quand Gradle le connaît. Les dépendances sans
 * fichier localisable sont ignorées silencieusement : seules les entrées
 * LOCALISABLES comptent pour un LSP.
 */
internal fun IdeaModule.versClasspathDto(): ModuleClasspathDto {
    val sources =
        contentRoots.all
            .flatMap { racine ->
                racine.sourceDirectories.all.map { it.directory } +
                    racine.testDirectories.all.map { it.directory }
            }.map { it.absolutePath }
            .distinct()
    val entrees =
        dependencies.all
            .mapNotNull { dependance ->
                when (dependance) {
                    is IdeaModuleDependency -> {
                        EntreeClasspathDto(
                            path = dependance.targetModuleName,
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
    return ModuleClasspathDto(name = name, sourceDirs = sources, entries = entrees)
}

/** Nature d'une entrée de classpath selon son fichier (jar, aar, dossier). */
private fun natureEntree(fichier: File): String =
    when {
        fichier.isDirectory -> "DOSSIER"
        fichier.extension.equals(other = "aar", ignoreCase = true) -> "AAR"
        else -> "JAR"
    }
