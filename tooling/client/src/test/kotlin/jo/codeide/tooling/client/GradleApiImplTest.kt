package jo.codeide.tooling.client

import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.ToolingReason
import jo.codeide.core.model.AppResult
import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.BuildRequest
import jo.codeide.tooling.protocol.BuildStarted
import jo.codeide.tooling.protocol.CancelRequest
import jo.codeide.tooling.protocol.ErrorCode
import jo.codeide.tooling.protocol.ErrorResponse
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HeapEvent
import jo.codeide.tooling.protocol.PartialSyncResult
import jo.codeide.tooling.protocol.StreamKind
import jo.codeide.tooling.protocol.SyncRequest
import jo.codeide.tooling.protocol.TaskInfo
import jo.codeide.tooling.protocol.TasksRequest
import jo.codeide.tooling.protocol.TasksResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Façade publique du client tooling (§5.3) sur session factice (§7.3) :
 * corrélation requête/réponse, états, diffusion **non conflatante** sous
 * forte charge (le test de la section 7.3 : beaucoup de lignes, rapidement,
 * aucune perdue).
 */
class GradleApiImplTest {
    /** API de test courante — fermée après CHAQUE test : les pompes des
     * sessions ne fuient pas d'un test à l'autre (sur une machine à 2
     * cœurs, des coroutines suspendues qui s'accumulent finissent par
     * perturber l'ordonnancement des suivantes). */
    private var api: GradleApiImpl? = null

    @org.junit.After
    fun nettoyer() {
        api?.fermerSession()
        api = null
    }

    private fun nouvelId(): String = UUID.randomUUID().toString()

    /** API enregistrée pour le nettoyage automatique en fin de test. */
    private fun nouvelleApi(): GradleApiImpl = GradleApiImpl().also { api = it }

    private val protocole = GradleProtocol.PROTOCOL_VERSION

    /** Cycle de vie minimal d'un build factice. */
    private suspend fun SessionFactice.jouerBuild(
        buildId: String,
        lignes: List<String>,
        reussi: Boolean = true,
    ) {
        emettre(BuildStarted(nouvelId(), protocole, buildId, listOf("saluer")))
        lignes.forEach { ligne ->
            emettre(BuildOutput(nouvelId(), protocole, buildId, StreamKind.STDOUT, ligne, ligne.hashCode().toLong()))
        }
        emettre(BuildFinished(nouvelId(), protocole, buildId, reussi, 1_234, if (reussi) null else "échec simulé"))
    }

    @Test
    fun `un build diffuse ses lignes dans l ordre et son etat final`() =
        runBlocking {
            val session =
                SessionFactice { requete, soi ->
                    if (requete is BuildRequest) {
                        soi.jouerBuild(requete.buildId, listOf("compilation", "tests", "packaging"))
                    }
                }
            val api = nouvelleApi()
            api.ouvrirSession(session)

            // Le canal tamponne les lignes émises PENDANT le build : la
            // souscription après le lancement ne perd rien (§5.2 — c'est la
            // garantie apportée par le canal borné vs un SharedFlow simple).
            val buildId = api.build(File("/p"), listOf("saluer"))
            val lignes = mutableListOf<LigneSortieBuild>()
            withTimeout(5_000) {
                api.observeBuildOutput(buildId).take(3).toList(lignes)
            }

            assertEquals(listOf("compilation", "tests", "packaging"), lignes.map { it.ligne })
            val etatFinal = api.observeBuildState(buildId).first { it.statut != StatutBuild.EN_COURS }
            assertEquals(StatutBuild.REUSSI, etatFinal.statut)
            assertEquals(1_234L, etatFinal.dureeMs)
        }

    @Test
    fun `la sortie d un build n est JAMAIS conflatee sous forte charge`() =
        runBlocking {
            // Section 7.3 : « envoyer un grand nombre de lignes rapidement,
            // vérifier qu'aucune n'est perdue » — 12 000 lignes émises d'un
            // trait dans un canal borné 4096 : l'envoi suspend (contre-pression),
            // le collecteur reçoit TOUT, dans l'ordre.
            val total = 12_000
            val session =
                SessionFactice { requete, soi ->
                    if (requete is BuildRequest) {
                        soi.emettre(BuildStarted(nouvelId(), protocole, requete.buildId, listOf("build")))
                        repeat(total) { index ->
                            soi.emettre(
                                BuildOutput(
                                    nouvelId(),
                                    protocole,
                                    requete.buildId,
                                    StreamKind.STDOUT,
                                    "ligne $index",
                                    index.toLong(),
                                ),
                            )
                        }
                        soi.emettre(BuildFinished(nouvelId(), protocole, requete.buildId, true, total.toLong()))
                    }
                }
            val api = nouvelleApi()
            api.ouvrirSession(session)

            val buildId = api.build(File("/p"), listOf("build"))
            val recues = mutableListOf<LigneSortieBuild>()
            withTimeout(30_000) {
                api.observeBuildOutput(buildId).take(total).toList(recues)
            }
            assertEquals("aucune ligne ne doit manquer", total, recues.size)
            assertEquals("première ligne intacte", "ligne 0", recues.first().ligne)
            assertEquals("dernière ligne intacte", "ligne ${total - 1}", recues.last().ligne)
        }

    @Test
    fun `la liste des taches traverse avec la corrélation d identifiant`() =
        runBlocking {
            val session =
                SessionFactice { requete, soi ->
                    if (requete is TasksRequest) {
                        soi.emettre(
                            TasksResult(
                                id = requete.id,
                                protocolVersion = protocole,
                                projectDir = requete.projectDir,
                                tasks =
                                    listOf(
                                        TaskInfo(":saluer", null, "saluer"),
                                        TaskInfo(":app:build", "build", "build"),
                                    ),
                            ),
                        )
                    }
                }
            val api = nouvelleApi()
            api.ouvrirSession(session)

            val resultat = api.taches(File("/p"))
            assertTrue(resultat is AppResult.Success)
            val taches = (resultat as AppResult.Success).value
            assertEquals(2, taches.size)
            assertEquals(":app:build", taches[1].chemin)
            assertEquals("build", taches[1].groupe)
            assertEquals("saluer", taches[0].nomAffiche)
        }

    @Test
    fun `une erreur typée de l orchestrateur devient un échec AppResult`() =
        runBlocking {
            val session =
                SessionFactice { requete, soi ->
                    if (requete is SyncRequest) {
                        soi.emettre(
                            ErrorResponse(
                                id = nouvelId(),
                                protocolVersion = protocole,
                                requestId = requete.id,
                                code = ErrorCode.PROTOCOL_VERSION_MISMATCH,
                                message = "versions incompatibles",
                            ),
                        )
                    }
                }
            val api = nouvelleApi()
            api.ouvrirSession(session)

            val resultat = api.synchroniser(File("/p"))
            assertTrue(resultat is AppResult.Failure)
            val erreur = (resultat as AppResult.Failure).error
            assertTrue(erreur is AppError.Tooling)
            assertEquals(ToolingReason.ProtocolVersion, (erreur as AppError.Tooling).code)
            assertEquals("versions incompatibles", erreur.message)
        }

    @Test
    fun `une synchronisation partielle reste un succes partiel`() =
        runBlocking {
            val session =
                SessionFactice { requete, soi ->
                    if (requete is SyncRequest) {
                        soi.emettre(
                            PartialSyncResult(
                                id = requete.id,
                                protocolVersion = protocole,
                                projectDir = requete.projectDir,
                                resolvedModels = listOf("gradle-project"),
                                failedModels = listOf("idea-project"),
                            ),
                        )
                    }
                }
            val api = nouvelleApi()
            api.ouvrirSession(session)

            val resultat = api.synchroniser(File("/p"))
            val sync = (resultat as AppResult.Success).value
            assertEquals(false, sync.reussie)
            assertEquals(true, sync.partielle)
            assertEquals(listOf("gradle-project"), sync.modelesResolus)
            assertEquals(listOf("idea-project"), sync.modelesEchoues)
        }

    @Test
    fun `l annulation envoie la requête de son build`() =
        runBlocking {
            val session = SessionFactice()
            val api = nouvelleApi()
            api.ouvrirSession(session)

            val buildId = api.build(File("/p"), listOf("endormir"))
            api.cancel(buildId)
            // cancel() est feu-and-forget sur la portée du pompe : laisser
            // l'envoi asynchrone se faire.
            attendreQue { session.messages.any { it is CancelRequest } }
            val annulation = session.messages.filterIsInstance<CancelRequest>().single()
            assertEquals(buildId, annulation.buildId)
        }

    @Test
    fun `le tas et l etat de connexion se mettent a jour`() =
        runBlocking {
            val session = SessionFactice()
            val api = nouvelleApi()
            api.ouvrirSession(session)
            assertEquals(EtatConnexion.CONNECTEE, api.observeConnectionState().first())

            session.emettre(HeapEvent(nouvelId(), protocole, usedMb = 42, maxMb = 512))
            val tas = api.observeHeap().first { it.moUtilises == 42L }
            assertEquals(512L, tas.moMax)

            api.fermerSession()
            assertEquals(EtatConnexion.DECONNECTEE, api.observeConnectionState().first())
        }

    @Test
    fun `sans session les opérations échouent proprement`() =
        runBlocking {
            val api = nouvelleApi()
            val sync = api.synchroniser(File("/p"))
            val taches = api.taches(File("/p"))
            assertTrue(sync is AppResult.Failure && (sync as AppResult.Failure).error is AppError.Tooling)
            assertTrue(taches is AppResult.Failure)
            val erreur = (sync as AppResult.Failure).error as AppError.Tooling
            assertEquals(ToolingReason.ConnectionLost, erreur.code)

            // build() sans session : l'échec se lit dans l'ÉTAT du build —
            // l'UI qui observe observeBuildState le voit immédiatement.
            val buildId = api.build(File("/p"), listOf("saluer"))
            val etat = api.observeBuildState(buildId).first()
            assertEquals(StatutBuild.ECHOUE, etat.statut)
            assertEquals("orchestrateur non connecté", etat.messageEchec)
        }

    @Test
    fun `un échec d envoi se traduit en échec de connexion`() =
        runBlocking {
            val session =
                SessionFactice { requete, soi ->
                    if (requete is TasksRequest) {
                        soi.emettre(
                            TasksResult(
                                id = requete.id,
                                protocolVersion = protocole,
                                projectDir = requete.projectDir,
                                tasks = emptyList(),
                            ),
                        )
                    }
                }
            val api = nouvelleApi()
            api.ouvrirSession(session)
            session.refuserEnvois()

            val resultat = api.taches(File("/p"))
            assertTrue(resultat is AppResult.Failure)
            assertEquals(
                ToolingReason.ConnectionLost,
                ((resultat as AppResult.Failure).error as AppError.Tooling).code,
            )
        }

    @Test
    fun `le flux stderr se distingue du stdout`() =
        runBlocking {
            val session =
                SessionFactice { requete, soi ->
                    if (requete is BuildRequest) {
                        soi.emettre(BuildStarted(nouvelId(), protocole, requete.buildId, listOf("b")))
                        soi.emettre(
                            BuildOutput(nouvelId(), protocole, requete.buildId, StreamKind.STDERR, "avertissement", 1),
                        )
                        soi.emettre(BuildFinished(nouvelId(), protocole, requete.buildId, true, 10))
                    }
                }
            val api = nouvelleApi()
            api.ouvrirSession(session)
            val buildId = api.build(File("/p"), listOf("b"))
            val lignes = mutableListOf<LigneSortieBuild>()
            withTimeout(5_000) {
                api.observeBuildOutput(buildId).take(1).toList(lignes)
            }
            assertEquals(FluxSortieBuild.STDERR, lignes.single().flux)
            assertEquals("avertissement", lignes.single().ligne)
        }

    @Test
    fun `un echec de build porte son message`() =
        runBlocking {
            val session =
                SessionFactice { requete, soi ->
                    if (requete is BuildRequest) {
                        soi.jouerBuild(requete.buildId, listOf("début"), reussi = false)
                    }
                }
            val api = nouvelleApi()
            api.ouvrirSession(session)
            val buildId = api.build(File("/p"), listOf("casse"))
            val etat = api.observeBuildState(buildId).first { it.statut != StatutBuild.EN_COURS }
            assertEquals(StatutBuild.ECHOUE, etat.statut)
            assertEquals("échec simulé", etat.messageEchec)
        }

    /** Attente déterministe (les effets asynchrones se pollent borné). */
    private fun attendreQue(condition: () -> Boolean) {
        val debut = System.currentTimeMillis()
        while (!condition()) {
            check(System.currentTimeMillis() - debut < 5_000) { "condition jamais atteinte" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `un diagnostic traverse vers observeDiagnostics`() =
        runBlocking {
            val session = SessionFactice()
            val api = nouvelleApi()
            api.ouvrirSession(session)

            session.emettre(
                jo.codeide.tooling.protocol.Diagnostic(
                    id = nouvelId(),
                    protocolVersion = protocole,
                    severity = jo.codeide.tooling.protocol.DiagnosticSeverity.ERROR,
                    file = "/p/src/Casse.java",
                    line = 7,
                    column = 9,
                    message = "';' attendu",
                    source = "javac",
                ),
            )
            val diagnostics = api.observeDiagnostics(File("/p")).first { it.isNotEmpty() }
            val diagnostic = diagnostics.single()
            assertEquals(jo.codeide.core.domain.SeveriteDiagnostic.ERREUR, diagnostic.severite)
            assertEquals("/p/src/Casse.java", diagnostic.fichier)
            assertEquals(7L, diagnostic.ligne)
            assertEquals("javac", diagnostic.source)
        }

    @Test
    fun `cancel sans session est un no-op silencieux`() =
        runBlocking {
            val session = SessionFactice()
            val api = nouvelleApi()
            // PAS de session ouverte : cancel ne doit rien envoyer ni planter.
            api.cancel("inexistant")
            assertEquals(0, session.messages.size)
        }

    @Test
    fun `un echec d envoi pendant build se lit dans l etat du build`() =
        runBlocking {
            val session = SessionFactice()
            val api = nouvelleApi()
            api.ouvrirSession(session)
            session.refuserEnvois()

            val buildId = api.build(File("/p"), listOf("saluer"))
            val etat = api.observeBuildState(buildId).first()
            assertEquals(StatutBuild.ECHOUE, etat.statut)
            assertEquals(true, etat.messageEchec!!.contains("envoi impossible"))
        }

    @Test
    fun `ouvrir une nouvelle session ferme la precedente`() =
        runBlocking {
            val premiere = SessionFactice()
            val seconde = SessionFactice()
            val api = nouvelleApi()
            api.ouvrirSession(premiere)
            assertEquals(EtatConnexion.CONNECTEE, api.observeConnectionState().first())

            api.ouvrirSession(seconde)
            // La fermeture de l'ancienne session est SYNCHRONE dans
            // ouvrirSession, mais le finally de son pompe annulé court à
            // part : attendre l'état stable plutôt que l'instantané.
            attendreQue { premiere.fermee }
            attendreQue { runBlocking { api.observeConnectionState().first() } == EtatConnexion.CONNECTEE }
        }

    @Test
    fun `une deconnexion rompt les requetes en attente`() =
        runBlocking {
            val session = SessionFactice()
            val api = nouvelleApi()
            api.ouvrirSession(session)

            // Requête en attente (aucune réponse scriptée) puis le canal
            // meurt. Unconfined : l'envoi part immédiatement, la suspension
            // sur la promesse rend la main à la boucle du test.
            val resultat =
                kotlinx.coroutines.coroutineScope {
                    val taches =
                        async(kotlinx.coroutines.Dispatchers.Unconfined) {
                            api.taches(File("/p"))
                        }
                    attendreQue { session.messages.isNotEmpty() }
                    session.deconnecter()
                    taches.await()
                }
            assertTrue(resultat is AppResult.Failure)
            assertEquals(
                ToolingReason.ConnectionLost,
                ((resultat as AppResult.Failure).error as AppError.Tooling).code,
            )
            assertEquals(EtatConnexion.DECONNECTEE, api.observeConnectionState().first())
        }
}
