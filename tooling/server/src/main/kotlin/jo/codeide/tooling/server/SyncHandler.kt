package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.PartialSyncResult
import jo.codeide.tooling.protocol.SyncRequest
import jo.codeide.tooling.protocol.SyncResult
import jo.codeide.tooling.protocol.SyncStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.idea.IdeaProject
import java.io.File

/**
 * Synchronisation de projet (§5.3 — Resilient Sync) : résout les modèles
 * de la Tooling API UN PAR UN — `GradleProject` (tâches) puis `IdeaProject`
 * (structure IDE) — et livre ce qui a été résolu même si l'autre échoue :
 * [PartialSyncResult] plutôt qu'un échec sec.
 *
 * Chaque modèle est résolu dans sa propre garde : l'échec de l'un
 * n'entraîne pas celui de l'autre (c'est la sémantique « résiliente »
 * demandée par le prompt ; elle est stable dans la Tooling API puisque
 * chaque `model().get()` est un appel indépendant).
 */
internal class SyncHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
) {
    /** Résout les modèles du projet et publie [SyncStarted] puis
     *  [SyncResult] ou [PartialSyncResult]. */
    suspend fun synchroniser(requete: SyncRequest) {
        val debut = System.currentTimeMillis()
        val dossier = File(requete.projectDir)

        // Départ annoncé AVANT toute résolution (étape 32, ADR 0057) :
        // symétrique du BuildStarted des builds — l'app rend son état
        // sur un événement DU serveur, pas sur la présomption de son
        // propre geste (une sync peut aussi partir d'un autre point
        // d'entrée demain).
        bus.publier(
            SyncStarted(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
            ),
        )

        val resolus = mutableListOf<String>()
        val echoues = mutableListOf<String>()

        resoudre("gradle-project", resolus, echoues) {
            pool.connexion(dossier).model(GradleProject::class.java).get()
        }
        resoudre("idea-project", resolus, echoues) {
            pool.connexion(dossier).model(IdeaProject::class.java).get()
        }

        when {
            resolus.isEmpty() -> {
                bus.publier(
                    SyncResult(
                        id = requete.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = requete.projectDir,
                        succeeded = false,
                        durationMs = System.currentTimeMillis() - debut,
                        failureMessage = "aucun modèle résolu (${echoues.joinToString()})",
                    ),
                )
            }

            echoues.isEmpty() -> {
                bus.publier(
                    SyncResult(
                        id = requete.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = requete.projectDir,
                        succeeded = true,
                        durationMs = System.currentTimeMillis() - debut,
                    ),
                )
            }

            else -> {
                bus.publier(
                    PartialSyncResult(
                        id = requete.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = requete.projectDir,
                        resolvedModels = resolus.toList(),
                        failedModels = echoues.toList(),
                    ),
                )
            }
        }
    }

    /** Résout un modèle isolé : son échec est constaté, jamais propagé. */
    private suspend fun resoudre(
        nom: String,
        resolus: MutableList<String>,
        echoues: MutableList<String>,
        resolution: suspend () -> Unit,
    ) {
        try {
            withContext(Dispatchers.IO) { resolution() }
            resolus += nom
        } catch (t: Throwable) {
            Journal.warn("modèle $nom non résolu : ${t.message}")
            echoues += nom
        }
    }
}
