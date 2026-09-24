package jo.codeide.tooling.server

import jo.codeide.tooling.api.ModeleModule
import jo.codeide.tooling.api.ModeleProjet
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.ModelRequest
import jo.codeide.tooling.protocol.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import java.io.File

/**
 * Résolution du modèle de projet (§3.2 : `ModelRequest`) : extrait
 * [ModeleProjet] (tooling:api — modules, chemins) depuis `IdeaProject`.
 *
 * Décision ADR 0040 : le catalogue figé de G1 ne prévoit pas de
 * `ModelResult` dédié — la sémantique de `ModelRequest` (« demande un
 * modèle de la Tooling API ») est la même que celle de la synchronisation :
 * la réponse est donc un [SyncResult] portant l'issue de la résolution, et
 * [ModeleProjet] est consigné au journal (les évolutions LSP le
 * transporteront — voir docs/TOOLING.md).
 */
internal class ModelHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
) {
    /** Résout le modèle du projet et publie le [SyncResult] correspondant. */
    suspend fun modele(requete: ModelRequest) {
        val debut = System.currentTimeMillis()
        val dossier = File(requete.projectDir)
        try {
            val modele =
                withContext(Dispatchers.IO) {
                    val idea = pool.connexion(dossier).model(IdeaProject::class.java).get()
                    idea.versModeleProjet(dossier)
                }
            Journal.info("modèle résolu : ${modele.nom} — ${modele.modules.size} modules")
            bus.publier(
                SyncResult(
                    id = nouvelId(),
                    protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                    projectDir = requete.projectDir,
                    succeeded = true,
                    durationMs = System.currentTimeMillis() - debut,
                ),
            )
        } catch (t: Throwable) {
            Journal.warn("modèle de projet non résolu : ${t.message}")
            bus.publier(
                SyncResult(
                    id = nouvelId(),
                    protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                    projectDir = requete.projectDir,
                    succeeded = false,
                    durationMs = System.currentTimeMillis() - debut,
                    failureMessage = t.message ?: "modèle de projet non résolu",
                ),
            )
        }
    }
}

/**
 * Traduction `IdeaProject` → [ModeleProjet] (tooling:api) : le nom vient du
 * répertoire racine (IdeaProject n'expose pas de nom de projet), le chemin
 * de chaque module de son `GradleProject` lié — méthodes historiques de la
 * Tooling API, jamais [org.gradle.tooling.model.UnsupportedMethodException]
 * sur ces deux-là.
 */
internal fun IdeaProject.versModeleProjet(racine: File): ModeleProjet =
    ModeleProjet(
        nom = racine.name,
        chemin = racine.canonicalPath,
        modules =
            modules.all.map { module: IdeaModule ->
                ModeleModule(
                    nom = module.name,
                    chemin = module.gradleProject?.projectDirectory?.canonicalPath ?: "",
                )
            },
    )
