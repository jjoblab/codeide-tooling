package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.ResolvedDependenciesRequest
import jo.codeide.tooling.protocol.ResolvedDependenciesResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Arbre des dépendances résolues (mission Projet P4, ADR 0095).
 *
 * Implémentation actuelle (P4) : réponse vide. Le branchement au
 * modèle `IdeaProject` de la Tooling API Gradle (qui exposerait
 * `IdeaSingleEntryLibraryDependency` avec `group`/`name`/`version`)
 * est un livrable P6+ : il nécessite de valider la résolution
 * transitive (récursion sur `IdeaDependency.getChildren()`), les
 * conflits (version demandée ≠ retenue via `dependencyInsight`),
 * et les tests d'intégration avec un projet AGP réel.
 *
 * L'app gère l'absence (message « aucune dépendance résolue ») —
 * le protocole reste cohérent : la réponse est émise, juste vide.
 */
@Suppress("TooManyFunctions")
internal class ResolvedDependenciesHandler(
    private val bus: EventBus,
) {
    /**
     * Publie [ResolvedDependenciesResult] : arbre résolu du module.
     *
     * P4 : réponse vide tant que le modèle IdeaProject n'est pas
     * branché (voir KDoc de la classe).
     */
    suspend fun resoudre(requete: ResolvedDependenciesRequest) {
        // Lecture du dossier juste pour vérifier qu'il existe — sinon
        // la réponse reste vide.
        withContext(Dispatchers.IO) {
            File(requete.projectDir).isDirectory
        }
        bus.publier(
            ResolvedDependenciesResult(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                module = requete.module,
                configuration = requete.configuration,
                racine = emptyList(),
            ),
        )
    }
}
