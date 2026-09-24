package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.TaskInfo
import jo.codeide.tooling.protocol.TasksRequest
import jo.codeide.tooling.protocol.TasksResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.GradleTask
import java.io.File

/**
 * Liste des tâches d'un projet (§5.3 — sélecteur « Exécuter ») : résout le
 * modèle [GradleProject] SANS exécuter de tâche, et parcourt l'arbre des
 * sous-projets — un projet multi-modules expose ses tâches qualifiées
 * (`:lib:compiler`) comme un IDE de bureau.
 */
internal class TasksHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
) {
    /** Publie [TasksResult] : toutes les tâches de l'arbre du projet. */
    suspend fun taches(requete: TasksRequest) {
        val dossier = File(requete.projectDir)
        val taches =
            withContext(Dispatchers.IO) {
                val racine = pool.connexion(dossier).model(GradleProject::class.java).get()
                val collectees = mutableListOf<TaskInfo>()
                parcourir(racine, collectees)
                collectees
            }
        bus.publier(
            TasksResult(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                tasks = taches,
            ),
        )
    }

    /** Parcours en profondeur : racine d'abord, sous-projets ensuite. */
    private fun parcourir(
        projet: GradleProject,
        collectees: MutableList<TaskInfo>,
    ) {
        projet.tasks.all.forEach { tache: GradleTask ->
            collectees +=
                TaskInfo(
                    path = tache.path,
                    group = tache.group,
                    displayName = tache.displayName,
                )
        }
        projet.children.all.forEach { enfant -> parcourir(enfant, collectees) }
    }
}
