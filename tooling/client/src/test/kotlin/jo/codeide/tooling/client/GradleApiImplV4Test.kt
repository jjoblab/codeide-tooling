package jo.codeide.tooling.client

import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.ToolingReason
import jo.codeide.core.model.AppResult
import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.DetailTelechargement
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.ProgressEvent
import jo.codeide.tooling.protocol.SyncPhase
import jo.codeide.tooling.protocol.SyncProgress
import jo.codeide.tooling.protocol.SyncRequest
import jo.codeide.tooling.protocol.SyncResult
import jo.codeide.tooling.protocol.SyncStarted
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
 * Tests client des comportements v4 (§3.1 + addendum §6) : délai
 * d'INACTIVITÉ de la sync (la fenêtre se réarme à chaque événement — un
 * téléchargement qui progresse ne meurt plus à 5 minutes) et canal des
 * téléchargements du build (tamponné dès le lancement, fermé à la fin).
 */
class GradleApiImplV4Test {
    private val protocole = GradleProtocol.PROTOCOL_VERSION

    private fun nouvelId(): String = UUID.randomUUID().toString()

    private fun nouvelleApi(): GradleApiImpl = GradleApiImpl()

    @Test
    fun `la sync survit tant que des evenements arrivent - delai d inactivite et non total`() =
        runBlocking {
            val session = SessionFactice()
            val api = nouvelleApi()
            api.ouvrirSession(session)
            // Fenêtre d'inactivité courte : l'ANCIEN comportement (délai
            // TOTAL de la même valeur) tuerait la sync dès la première
            // fenêtre — le réarmement la laisse vivre tant qu'elle progresse.
            api.delaiInactiviteSyncMs = 300L

            val sync = async { api.synchroniser(File("/p")) }
            // Émission CONCURRENTE (la séquence doit avancer PENDANT que la
            // promesse attend) : 4 progressions espacées de 150 ms, résultat
            // à ~750 ms — chaque fenêtre de 300 ms se réarme en chemin.
            launch {
                val requete =
                    withTimeout(5_000) {
                        while (session.messages.isEmpty()) {
                            delay(10)
                        }
                        session.messages.first() as SyncRequest
                    }
                session.emettre(SyncStarted(nouvelId(), protocole, requete.projectDir))
                repeat(4) { indice ->
                    delay(150)
                    session.emettre(
                        SyncProgress(
                            nouvelId(),
                            protocole,
                            requete.projectDir,
                            SyncPhase.DEPENDANCES,
                            octetsRecus = 1_000L * (indice + 1),
                            compteur = indice + 1,
                        ),
                    )
                }
                delay(150)
                session.emettre(
                    SyncResult(
                        id = requete.id,
                        protocolVersion = protocole,
                        projectDir = requete.projectDir,
                        succeeded = true,
                        durationMs = 800,
                    ),
                )
            }

            val resultat = withTimeout(10_000) { sync.await() }
            assertTrue(
                "une sync qui progresse devait survivre : $resultat",
                (resultat as AppResult.Success).value.reussie,
            )
        }

    @Test
    fun `la sync muette echoue au bout du delai d inactivite`() =
        runBlocking {
            val session = SessionFactice()
            val api = nouvelleApi()
            api.ouvrirSession(session)
            api.delaiInactiviteSyncMs = 200L

            val sync = async { api.synchroniser(File("/p")) }
            launch {
                val requete =
                    withTimeout(5_000) {
                        while (session.messages.isEmpty()) {
                            delay(10)
                        }
                        session.messages.first() as SyncRequest
                    }
                delay(50)
                // Un signe de vie initial, puis PLUS RIEN : la fenêtre
                // (réarmée à 50 ms) expire vers 400 ms.
                session.emettre(SyncStarted(nouvelId(), protocole, requete.projectDir))
            }

            val resultat = withTimeout(5_000) { sync.await() }
            assertTrue("une sync muette devait échouer : $resultat", resultat is AppResult.Failure)
            assertEquals(
                ToolingReason.Timeout,
                (resultat as AppResult.Failure).error.let { (it as? AppError.Tooling)?.code },
            )
        }

    @Test
    fun `les telechargements du build traversent vers leur canal puis il se ferme`() =
        runBlocking {
            val session = SessionFactice()
            val api = nouvelleApi()
            api.ouvrirSession(session)

            val collecte =
                launch {
                    // Souscription DIFFÉRÉE : le canal est tamponné dès build(),
                    // les téléchargements précoces attendent le collecteur.
                    delay(100)
                }
            val buildId = api.build(File("/p"), listOf("saluer"))
            collecte.join()

            session.emettre(
                ProgressEvent(
                    nouvelId(),
                    protocole,
                    buildId,
                    message = "Téléchargement kotlin-stdlib.jar",
                    telechargement =
                        DetailTelechargement(
                            element = "kotlin-stdlib.jar",
                            octetsRecus = 1_769_000,
                            octetsTotal = 1_769_000,
                            termine = true,
                            dureeMs = 850,
                            compteur = 3,
                        ),
                ),
            )
            // Un ProgressEvent TEXTUEL (sans détail) ne traverse pas.
            session.emettre(ProgressEvent(nouvelId(), protocole, buildId, message = "statut générique"))
            session.emettre(BuildFinished(nouvelId(), protocole, buildId, succeeded = true, durationMs = 1))

            val telechargements =
                withTimeout(5_000) {
                    api.observeTelechargementsBuild(buildId).toList()
                }
            assertEquals(1, telechargements.size)
            assertEquals("kotlin-stdlib.jar", telechargements.single().element)
            assertEquals(1_769_000L, telechargements.single().octetsRecus)
            assertTrue(telechargements.single().termine)
            assertEquals(3, telechargements.single().compteur)
        }
}
