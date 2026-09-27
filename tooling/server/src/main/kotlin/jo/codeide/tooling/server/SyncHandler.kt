package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.PartialSyncResult
import jo.codeide.tooling.protocol.SyncPhase
import jo.codeide.tooling.protocol.SyncProgress
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
 *
 * v3 (progression de sync) : chaque phase est annoncée AU bus par
 * [SyncProgress] — départ puis durée — entre [SyncStarted] et le résultat :
 * l'app affiche « connexion au daemon Gradle… », « résolution du modèle… »
 * au lieu d'un « en cours » muet pendant des dizaines de secondes (la
 * première connexion d'un projet télécharge la distribution Gradle et
 * démarre son daemon). Les phases voyagent ÉNUMÉRÉES : les libellés
 * appartiennent au client, jamais au serveur.
 */
internal class SyncHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
) {
    /**
     * Résout les modèles du projet et publie [SyncStarted], les phases
     * ([SyncProgress]) puis [SyncResult] ou [PartialSyncResult].
     */
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

        // Connexion hoistée AVANT les modèles (v3) : elle porte sa PROPRE
        // phase — la plus longue d'une première sync (distribution +
        // daemon) mérite son affichage, et son échec sec évite deux
        // « modèles non résolus » qui disent tout sauf la cause.
        val enCours = EnCoursSync(requete = requete)
        val connexion =
            try {
                mesurerPhase(requete, SyncPhase.CONNEXION) { pool.connexion(dossier) }
            } catch (t: Throwable) {
                Journal.warn("connexion Gradle impossible : ${t.message}")
                bus.publier(
                    SyncResult(
                        id = requete.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = requete.projectDir,
                        succeeded = false,
                        durationMs = System.currentTimeMillis() - debut,
                        failureMessage = "connexion Gradle impossible : ${t.message}",
                    ),
                )
                return
            }

        resoudre(enCours, "gradle-project", SyncPhase.MODELE_GRADLE) {
            connexion.model(GradleProject::class.java).get()
        }
        resoudre(enCours, "idea-project", SyncPhase.MODELE_IDEA) {
            connexion.model(IdeaProject::class.java).get()
        }
        publierIssue(enCours, System.currentTimeMillis() - debut)
    }

    /** Accumulateurs d'une sync en cours (modèles résolus/échoués). */
    private class EnCoursSync(
        val requete: SyncRequest,
        val resolus: MutableList<String> = mutableListOf(),
        val echoues: MutableList<String> = mutableListOf(),
    )

    /**
     * Résout un modèle isolé : sa phase est annoncée au bus (départ puis
     * durée), son échec est constaté, jamais propagé.
     */
    private suspend fun resoudre(
        enCours: EnCoursSync,
        nom: String,
        phase: SyncPhase,
        resolution: suspend () -> Unit,
    ) {
        try {
            mesurerPhase(enCours.requete, phase) {
                withContext(Dispatchers.IO) { resolution() }
            }
            enCours.resolus += nom
        } catch (t: Throwable) {
            Journal.warn("modèle $nom non résolu : ${t.message}")
            enCours.echoues += nom
        }
    }

    /** Publie l'issue de la sync : réussie, partielle ou échec sec. */
    private fun publierIssue(
        enCours: EnCoursSync,
        dureeMs: Long,
    ) {
        val requete = enCours.requete
        when {
            enCours.resolus.isEmpty() -> {
                bus.publier(
                    SyncResult(
                        id = requete.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = requete.projectDir,
                        succeeded = false,
                        durationMs = dureeMs,
                        failureMessage = "aucun modèle résolu (${enCours.echoues.joinToString()})",
                    ),
                )
            }

            enCours.echoues.isEmpty() -> {
                bus.publier(
                    SyncResult(
                        id = requete.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = requete.projectDir,
                        succeeded = true,
                        durationMs = dureeMs,
                    ),
                )
            }

            else -> {
                bus.publier(
                    PartialSyncResult(
                        id = requete.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = requete.projectDir,
                        resolvedModels = enCours.resolus.toList(),
                        failedModels = enCours.echoues.toList(),
                    ),
                )
            }
        }
    }

    /**
     * Exécute [bloc] entre les deux annonces de sa phase (v3) : départ
     * (`terminee = false`) puis retour avec durée (`terminee = true`) —
     * l'horloge est celle du serveur, la même que [SyncResult.durationMs].
     */
    private suspend fun <T> mesurerPhase(
        requete: SyncRequest,
        phase: SyncPhase,
        bloc: suspend () -> T,
    ): T {
        publierPhase(requete, phase, terminee = false, dureeMs = 0)
        val debut = System.currentTimeMillis()
        try {
            return bloc()
        } finally {
            publierPhase(requete, phase, terminee = true, dureeMs = System.currentTimeMillis() - debut)
        }
    }

    private fun publierPhase(
        requete: SyncRequest,
        phase: SyncPhase,
        terminee: Boolean,
        dureeMs: Long,
    ) {
        bus.publier(
            SyncProgress(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                phase = phase,
                terminee = terminee,
                dureeMs = dureeMs,
            ),
        )
    }
}
