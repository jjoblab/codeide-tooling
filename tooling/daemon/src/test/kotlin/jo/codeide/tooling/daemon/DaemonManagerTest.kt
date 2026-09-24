package jo.codeide.tooling.daemon

import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.ManagedProcess
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeNativeProcessLauncher
import jo.codeide.core.testing.FakeToolchainLocator
import jo.codeide.tooling.client.EchecHandshakeClient
import jo.codeide.tooling.client.GradleApiImpl
import jo.codeide.tooling.client.SessionTooling
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.PingMessage
import jo.codeide.tooling.protocol.PongMessage
import jo.codeide.tooling.protocol.ProtocolMessage
import jo.codeide.tooling.protocol.ToolingEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests du [DaemonManager] sur fakes (§5.4) : ordre écoute-avant-lancement,
 * réinjection du stderr, relances bornées, JDK absent, handshake refusé,
 * health check, arrêt. Le bout-en-bout RÉEL (process, socket, build Gradle)
 * vit dans [BoutEnBoutTest].
 */
class DaemonManagerTest {
    private val api = GradleApiImpl()
    private val journal = FakeAppLogger()
    private val lanceur = FakeNativeProcessLauncher()
    private val outils = FakeToolchainLocator().apply { jdk = File("/fake/jdk") }
    private val hote = HoteSocketFactice()
    private val deployeur =
        JarDeployer(
            source = SourceJarMemoire(CONTENU_JAR),
            dossierCible = dossierTemporaire("deploy"),
            dispatchers = DispatchersIoDirect(),
        )
    private var daemon: DaemonManager? = null
    private var portee: CoroutineScope? = null

    @After
    fun nettoyer() {
        daemon?.arreter()
        portee?.cancel()
        portee = null
        api.fermerSession()
    }

    /** Daemon monté sur les fakes, cadences de test (santé et relances rapides). */
    private fun nouveauDaemon(
        delaiRelanceMs: Long = 1L,
        intervalleSanteMs: Long = 50L,
        delaiSanteMs: Long = 250L,
    ): DaemonManager =
        DaemonManager(
            lanceur = lanceur,
            outilchain = outils,
            api = api,
            hote = hote,
            deployeur = deployeur,
            journal = journal,
            dispatchers = DispatchersIoDirect(),
            fabriqueCommande = { java, jar, cheminSocket, secret ->
                listOf(java.path, "-jar", jar.path, "--socket", cheminSocket.path, "--secret", secret)
            },
            intervalleSanteMs = intervalleSanteMs,
            delaiSanteMs = delaiSanteMs,
            delaiRelanceMs = delaiRelanceMs,
        )

    /** Démarre le daemon dans sa portée de test et l'enregistre pour le nettoyage. */
    private fun DaemonManager.demarrerEnTest() {
        daemon = this
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        portee = scope
        demarrer(scope)
    }

    @Test
    fun `l ecoute s ouvre AVANT le lancement et la session suit le handshake`() =
        runBlocking {
            val session = SessionFacticeDaemon(pongAuto = true)
            hote.sessions.addAll(listOf(session))
            // Process vivant : la surveillance tient jusqu'à la fin du test.
            lanceur.fabrique = { ProcessusMaitrise() }
            nouveauDaemon().demarrerEnTest()

            attendreQue { api.etatConnexion == EtatConnexion.CONNECTEE }

            assertEquals(1, lanceur.lancements.size)
            // §5.1 : l'écoute précède le lancement — l'hôte l'a constaté.
            assertTrue("ouvrir() devait précéder le lancement", hote.ordreOuvertureAvantLancement)
            // Le secret est passé au process ET capté au retour (§4.4) — le
            // health check le prouve vivant : des pings partent DANS la
            // session ouverte.
            val commande = lanceur.lancements[0].command
            val indiceSecret = commande.indexOf("--secret")
            assertTrue("la commande devait porter le secret", indiceSecret >= 0)
            assertEquals(commande[indiceSecret + 1], hote.secretsRecus[0])
            assertTrue("le secret devait être non trivial", hote.secretsRecus[0].length >= 32)
            attendreQue { session.envoyes.filterIsInstance<PingMessage>().isNotEmpty() }
        }

    @Test
    fun `le stderr du process rejoint le journal sous le tag gradle-server`() =
        runBlocking {
            hote.sessions.addAll(listOf(SessionFacticeDaemon(pongAuto = true)))
            lanceur.fabrique = { ProcessusMaitrise(lignesStderr = listOf("boom orchestrateur")) }
            nouveauDaemon().demarrerEnTest()

            attendreQue { journal.entries.any { it.tag == "gradle-server" && it.message.contains("boom") } }
        }

    @Test
    fun `la mort du process declenche une relance avec un secret neuf`() =
        runBlocking {
            val premiere = ProcessusMaitrise()
            val seconde = ProcessusMaitrise()
            val numero =
                java.util.concurrent.atomic
                    .AtomicInteger()
            lanceur.fabrique = { _ ->
                if (numero.incrementAndGet() == 1) premiere else seconde
            }
            hote.sessions.addAll(listOf(SessionFacticeDaemon(pongAuto = true), SessionFacticeDaemon(pongAuto = true)))
            nouveauDaemon().demarrerEnTest()

            attendreQue { api.etatConnexion == EtatConnexion.CONNECTEE && lanceur.lancements.size == 1 }
            premiere.mourir(code = 3)

            attendreQue { lanceur.lancements.size == 2 }
            attendreQue { api.etatConnexion == EtatConnexion.CONNECTEE }
            assertEquals(2, hote.secretsRecus.size)
            assertNotEquals(
                "chaque tentative devait générer un secret neuf",
                hote.secretsRecus[0],
                hote.secretsRecus[1],
            )
        }

    @Test
    fun `cinq echecs epuisent les tentatives et laissent la connexion ECHOUEE`() =
        runBlocking {
            // Chaque process meurt dès son lancement (connexion aussitôt perdue).
            lanceur.fabrique = { ProcessusMaitrise().also { it.mourir(code = 3) } }
            repeat(6) { hote.sessions.add(SessionFacticeDaemon(pongAuto = true)) }
            nouveauDaemon().demarrerEnTest()

            attendreQue { api.etatConnexion == EtatConnexion.ECHOUEE }
            assertEquals(GradleProtocol.MAX_RECONNECT_ATTEMPTS, lanceur.lancements.size)
        }

    @Test
    fun `JDK absent, aucun lancement et connexion DECONNECTEE`() =
        runBlocking {
            outils.jdk = null
            nouveauDaemon().demarrerEnTest()

            attendreQue { journal.entries.any { it.message.contains("JDK") } }
            Thread.sleep(200)
            assertEquals(0, lanceur.lancements.size)
            assertEquals(EtatConnexion.DECONNECTEE, api.etatConnexion)
        }

    @Test
    fun `un handshake refuse est un echec definitif sans relance`() =
        runBlocking {
            hote.refus = EchecHandshakeClient("version de protocole incompatible : 99")
            nouveauDaemon().demarrerEnTest()

            attendreQue { api.etatConnexion == EtatConnexion.ECHOUEE }
            assertEquals(1, lanceur.lancements.size)
            assertTrue(journal.entries.any { it.message.contains("handshake refusé") })
        }

    @Test
    fun `un orchestrateur muet est tue par le health check puis relance`() =
        runBlocking {
            val muet = ProcessusMaitrise()
            val sain = ProcessusMaitrise()
            val numero =
                java.util.concurrent.atomic
                    .AtomicInteger()
            lanceur.fabrique = { _ ->
                if (numero.incrementAndGet() == 1) muet else sain
            }
            // Première session : AUCUN pong ; seconde : pong automatique.
            hote.sessions.addAll(listOf(SessionFacticeDaemon(pongAuto = false), SessionFacticeDaemon(pongAuto = true)))
            nouveauDaemon().demarrerEnTest()

            attendreQue { muet.tues.isNotEmpty() && lanceur.lancements.size == 2 }
            attendreQue { api.etatConnexion == EtatConnexion.CONNECTEE }
        }

    @Test
    fun `arreter tue le process sans relance et laisse DECONNECTEE`() =
        runBlocking {
            val process = ProcessusMaitrise()
            lanceur.fabrique = { process }
            hote.sessions.addAll(listOf(SessionFacticeDaemon(pongAuto = true)))
            val daemonTest = nouveauDaemon()
            daemonTest.demarrerEnTest()

            attendreQue { api.etatConnexion == EtatConnexion.CONNECTEE }
            daemonTest.arreter()

            // L'annulation est asynchrone : le nettoyage synchrone du finally
            // de la tentative s'exécute sur le dispatcher, on attend l'effet.
            attendreQue { process.tues.isNotEmpty() }
            attendreQue { api.etatConnexion == EtatConnexion.DECONNECTEE }
            Thread.sleep(300)
            assertEquals("aucune relance après un arrêt demandé", 1, lanceur.lancements.size)
        }

    @Test
    fun `la commande par defaut porte java tas jar socket secret et journalisation`() =
        runBlocking {
            val commande =
                commandeParDefaut().invoke(
                    File("/outils/jdk/bin/java"),
                    File("/donnees/files/tooling/gradle-server.jar"),
                    File("/donnees/files/run/gradle.sock"),
                    "secret-de-test",
                )
            assertEquals(
                listOf(
                    "/outils/jdk/bin/java",
                    "-Xmx${TAS_MO}",
                    "-jar",
                    "/donnees/files/tooling/gradle-server.jar",
                    "--socket",
                    "/donnees/files/run/gradle.sock",
                    "--secret",
                    "secret-de-test",
                    "--log-level",
                    "INFO",
                ),
                commande,
            )
        }

    @Test
    fun `un JAR indisponible est un echec definitif sans lancement`() =
        runBlocking {
            val deployeurCassee =
                JarDeployer(
                    source = SourceJarAbsent(),
                    dossierCible = dossierTemporaire("deploy-casse"),
                    dispatchers = DispatchersIoDirect(),
                )
            val daemon =
                DaemonManager(
                    lanceur = lanceur,
                    outilchain = outils,
                    api = api,
                    hote = hote,
                    deployeur = deployeurCassee,
                    journal = journal,
                    dispatchers = DispatchersIoDirect(),
                    delaiRelanceMs = 1L,
                )
            daemon.demarrerEnTest()

            attendreQue { api.etatConnexion == EtatConnexion.ECHOUEE }
            assertEquals(0, lanceur.lancements.size)
            assertTrue(journal.entries.any { it.message.contains("indisponible") })
        }

    @Test
    fun `un code de sortie arguments invalides est un echec definitif`() =
        runBlocking {
            // Le process meurt immédiatement avec le code 2 (arguments
            // invalides) : relancer la MÊME commande est vain.
            lanceur.fabrique = { ProcessusMaitrise().also { it.mourir(code = 2) } }
            hote.sessions.addAll(listOf(SessionFacticeDaemon(pongAuto = true)))
            nouveauDaemon().demarrerEnTest()

            attendreQue { api.etatConnexion == EtatConnexion.ECHOUEE }
            assertEquals(1, lanceur.lancements.size)
        }

    // ------------------------------------------------------------------
    // Aides.
    // ------------------------------------------------------------------

    /** Attend une condition par sondage borné (même patron que G3). */
    private fun attendreQue(
        delaiMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val debut = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - debut > delaiMs) {
                throw AssertionError(
                    "condition non atteinte en $delaiMs ms — journal : ${journal.entries.joinToString {
                        "${it.tag}:${it.message}"
                    }}",
                )
            }
            Thread.sleep(20)
        }
    }

    private companion object {
        val CONTENU_JAR = ByteArray(2048) { octet -> (octet % 251).toByte() }
    }
}

/**
 * Hôte de socket factice : enregistre l'ordre des appels (§5.1), sert les
 * sessions programmées, capture les secrets reçus, peut refuser.
 */
private class HoteSocketFactice : HoteSocketTooling {
    val ordre = CopyOnWriteArrayList<String>()
    val sessions = ArrayDeque<SessionFacticeDaemon>()
    val secretsRecus = CopyOnWriteArrayList<String>()

    /** Quand non nul, accepterUneFois lève cette exception (handshake refusé). */
    var refus: EchecHandshakeClient? = null

    override val cheminSocket: File = File(dossierTemporaire("sockets"), GradleProtocol.SOCKET_NAME)

    /** `true` si ouvrir() a été appelé avant le moindre accepter (§5.1). */
    val ordreOuvertureAvantLancement: Boolean
        get() = ordre.indexOf("ouvrir") == 0

    override fun ouvrir() {
        ordre += "ouvrir"
    }

    override suspend fun accepterUneFois(
        secretAttendu: String,
        delaiMs: Long,
    ): SessionTooling {
        secretsRecus += secretAttendu
        refus?.let { throw it }
        return synchronized(sessions) { sessions.removeFirstOrNull() }
            ?: SessionFacticeDaemon(pongAuto = true)
    }

    override fun fermer() {
        ordre += "fermer"
    }
}

/**
 * Session factice locale (miroir de la SessionFactice de G3, hors module) :
 * canal illimité d'événements, envois capturés, pong automatique optionnel.
 */
private class SessionFacticeDaemon(
    private val pongAuto: Boolean,
) : SessionTooling {
    val envoyes = CopyOnWriteArrayList<ProtocolMessage>()
    private val canal = Channel<ToolingEvent>(Channel.UNLIMITED)

    override suspend fun envoyer(message: ProtocolMessage) {
        envoyes += message
        if (pongAuto && message is PingMessage) {
            canal.trySend(PongMessage(message.id, message.protocolVersion))
        }
    }

    override val evenements = canal.receiveAsFlow()

    override fun fermer() = Unit
}

/**
 * Process maîtrisé : la mort est DECLENCHÉE par le test (`mourir`) ou par
 * `kill`, les sorties sont scriptées — la relance, le health check et
 * l'attente de sortie se rejouent fidèlement.
 */
private class ProcessusMaitrise(
    lignesStderr: List<String> = emptyList(),
) : ManagedProcess {
    private val fin = CompletableDeferred<Int>()
    private val lignes = lignesStderr

    /** Appels à kill (valeur de `force`), dans l'ordre. */
    val tues = CopyOnWriteArrayList<Boolean>()

    override val pid: Int = 42_424

    override fun isAlive(): Boolean = !fin.isCompleted

    override fun stdoutLines(): Flow<String> = emptyList<String>().asFlow()

    override fun stderrLines(): Flow<String> = lignes.asFlow()

    override suspend fun awaitExit(): Int = fin.await()

    /** Fait mourir le process avec un code de sortie. */
    fun mourir(code: Int) {
        fin.complete(code)
    }

    override fun kill(force: Boolean) {
        tues += force
        fin.complete(CODE_TUE)
    }

    private companion object {
        const val CODE_TUE = 137
    }
}

/** Répertoire temporaire nommé (nettoyage non critique : tmp du système). */
internal fun dossierTemporaire(nom: String): File {
    val base = File(System.getProperty("java.io.tmpdir", "/tmp"), "codeide-daemon-tests")
    val dossier = File(base, nom + "-" + System.nanoTime())
    dossier.mkdirs()
    return dossier
}
