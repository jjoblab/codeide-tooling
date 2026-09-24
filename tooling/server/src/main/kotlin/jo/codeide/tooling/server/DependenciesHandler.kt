package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.DependenciesRequest
import jo.codeide.tooling.protocol.DependenciesResult
import jo.codeide.tooling.protocol.DependencyInfo
import jo.codeide.tooling.protocol.GradleProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import java.io.File

/**
 * Graphe de dépendances d'un projet (§3.2 : `DependenciesRequest`) : les
 * dépendances INTER-PROJETS de chaque module (IdeaModuleDependency — c'est
 * ce que « module + configuration » désigne dans le catalogue) ; les
 * dépendances de fichiers/bibliothèques externes ne portent pas de
 * « module » au sens du protocole et restent hors de cette réponse
 * (décision ADR 0040 — le sélecteur d'exécution n'en a pas l'usage).
 */
internal class DependenciesHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
) {
    /** Publie [DependenciesResult] : dépendances inter-modules du projet. */
    suspend fun dependances(requete: DependenciesRequest) {
        val dossier = File(requete.projectDir)
        val dependances =
            withContext(Dispatchers.IO) {
                val idea = pool.connexion(dossier).model(IdeaProject::class.java).get()
                idea.modules.all
                    .flatMap { module: IdeaModule -> module.dependencies.all }
                    .filterIsInstance<IdeaModuleDependency>()
                    .map { dependance ->
                        DependencyInfo(
                            module = dependance.targetModuleName,
                            configuration = dependance.scope.scope,
                        )
                    }.distinct()
            }
        bus.publier(
            DependenciesResult(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                dependencies = dependances,
            ),
        )
    }
}
