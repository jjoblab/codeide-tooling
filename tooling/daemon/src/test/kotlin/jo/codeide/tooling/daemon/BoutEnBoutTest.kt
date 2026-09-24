package jo.codeide.tooling.daemon

import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.ManagedProcess
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeToolchainLocator
import jo.codeide.tooling.client.GradleApiImpl
import jo.codeide.tooling.client.SessionTooling
import jo.codeide.tooling.protocol.FrameCodec
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HelloRequest
import jo.codeide.tooling.protocol.HelloResponse
import jo.codeide.tooling.protocol.ProtocolJson
import jo.codeide.tooling.protocol.ProtocolMessage
import jo.codeide.tooling.protocol.ToolingEvent
import jo.codeide.tooling.testing.FixturesGradle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BOUT-EN-BOUT RÉEL du tooling (§7.4) : le [DaemonManager] lance le VRAI
 * orchestrateur — un sous-processus `java` séparé exécutant `ServerMain`
 * (tooling:server) — sur un VRAI socket Unix, avec un VRAI build Gradle
 * sur la fixture `minimal-java`.
 *
 * Seules coutures non production : l'hôte du socket est l'implémentation
 * JDK (`ServerSocketChannel` Unix, même protocole que l'astuce
 * `LocalSocket` Android de l'ADR 0041) et la commande lance
 * `ServerMain` par classpath plutôt que le JAR déployé (l'artefact
 * shadowJar n'existe pas dans la JVM de test) — le dialogue complet
 * (lancement, handshake au secret, health check ping/pong, build,
 * sortie, arrêt) est réel de bout en bout.
 */
class BoutEnBoutTest {
    private val api = GradleApiImpl()
    private val journal = FakeAppLogger()
    private val temporaires = mutableListOf<File>()
    private var daemon: DaemonManager? = null
    private var portee: CoroutineScope? = null

    @org.junit.After
    fun nettoyer() {
        daemon?.arreter()
        portee?.cancel()
        portee = null
        api.fermerSession()
        temporaires.forEach { it.deleteRecursively() }
        temporaires.clear()
    }

    @Test
    fun `le daemon lance l orchestrateur reel jusqu a un build reel reussi`() =
        runBlocking {
            val fixture = copierFixture("minimal-java")
            val hote = HoteSocketJvm(dossierTemporaire("e2e-socket"))
            val daemonReel = monterDaemon(hote)
            daemon = daemonReel
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            portee = scope
            daemonReel.demarrer(scope)

            // 1. Connexion réelle : le process s'est connecté ET le
            //    handshake au secret a été validé par l'hôte.
            attendreQue(DELAI_CONNEXION) { api.etatConnexion == EtatConnexion.CONNECTEE }
            assertEquals(GradleProtocol.PROTOCOL_VERSION, hote.dernierHelloRecu?.protocolVersion)
            assertTrue("le secret devait correspondre entre process et hôte", hote.secretValide)

            // 2. Health check réel : le daemon ping, le VRAI orchestrateur
            //    répond pong — le repère bouge.
            val pongInitial = api.dernierPongMs.value
            attendreQue(DELAI_SANTE) { api.dernierPongMs.value > pongInitial }

            // 3. Build réel sur la fixture : sortie diffusée ligne à ligne,
            //    état final REUSSI.
            val buildId = api.build(fixture, listOf("saluer"))
            val lignes =
                withTimeout(DELAI_BUILD) {
                    api.observeBuildOutput(buildId).toList()
                }
            val etatFinal =
                withTimeout(DELAI_BUILD) {
                    api.observeBuildState(buildId).first { it.statut != StatutBuild.EN_COURS }
                }
            assertEquals(StatutBuild.REUSSI, etatFinal.statut)
            assertTrue(
                "la sortie du build réel devait contenir le salut : ${lignes.map { it.ligne }}",
                lignes.any { it.ligne.contains("Bonjour depuis minimal-java") },
            )

            // 4. Arrêt propre : le process est tué, la connexion retombe.
            daemonReel.arreter()
            attendreQue(DELAI_ARRET) { api.etatConnexion == EtatConnexion.DECONNECTEE }
        }

    /**
     * Monte le daemon sur l'hôte JDK avec le VRAI lancement de process
     * (classpath — l'artefact shadowJar n'existe pas en JVM de test).
     */
    private fun monterDaemon(hote: HoteSocketJvm): DaemonManager {
        val outils =
            FakeToolchainLocator().apply {
                jdk = File(javaMaison)
            }
        val deployeur =
            JarDeployer(
                source = SourceJarMemoire(ByteArray(128)),
                dossierCible = dossierTemporaire("e2e-jar"),
                dispatchers = DispatchersIoDirect(),
            )
        return DaemonManager(
            lanceur = LanceurProcessusReel(),
            outilchain = outils,
            api = api,
            hote = hote,
            deployeur = deployeur,
            journal = journal,
            dispatchers = DispatchersIoDirect(),
            fabriqueCommande = { java, _, cheminSocket, secret ->
                listOf(
                    java.absolutePath,
                    // Tas borné (production : 256 Mio — même discipline en
                    // test, la machine de CI n'a que 4 Go).
                    "-Xmx256m",
                    "-cp",
                    System.getProperty("java.class.path"),
                    CLASSE_ORCHESTRATEUR,
                    "--socket",
                    cheminSocket.absolutePath,
                    "--secret",
                    secret,
                    "--log-level",
                    "INFO",
                    "--heap-intervalle-ms",
                    "0",
                )
            },
        )
    }

    // ------------------------------------------------------------------
    // Aides.
    // ------------------------------------------------------------------

    private fun copierFixture(nom: String): File {
        val dossier = dossierTemporaire("e2e-$nom")
        temporaires.add(dossier)
        return FixturesGradle.copier(nom, dossier.toPath()).toFile()
    }

    /** Attend une condition par sondage borné. */
    private fun attendreQue(
        delaiMs: Long,
        condition: () -> Boolean,
    ) {
        val debut = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - debut > delaiMs) {
                throw AssertionError(
                    "condition non atteinte en $delaiMs ms — journal : " +
                        journal.entries.take(30).joinToString { "${it.tag}: ${it.message}" },
                )
            }
            Thread.sleep(50)
        }
    }

    private companion object {
        /** Classe principale du VRAI orchestrateur (sous-processus). */
        const val CLASSE_ORCHESTRATEUR = "jo.codeide.tooling.server.ServerMain"

        /** Racine du JDK courant (le test tourne SOUS un JDK, il l'exige). */
        val javaMaison: String = checkNotNull(System.getProperty("java.home")) { "java.home absent" }

        /** Connexion du sous-processus (lancement JVM + handshake). */
        const val DELAI_CONNEXION: Long = 30_000

        /** Premier pong du health check (intervalle de 5 s). */
        const val DELAI_SANTE: Long = 20_000

        /** Build réel (premier daemon Gradle ~15 s une seule fois). */
        const val DELAI_BUILD: Long = 180_000

        /** Arrêt du process (kill + EOF). */
        const val DELAI_ARRET: Long = 15_000
    }
}

/**
 * Hôte du socket côté JVM de test : `ServerSocketChannel` Unix — miroir
 * exact du rôle de `GradleSocketServer` (ADR 0041) : accept, lecture de la
 * première frame (HelloRequest), validation du secret PAR ASSERTION (le
 * test EST l'app), réponse HelloResponse, puis session JDK.
 *
 * G6 (chaos §7.5) : la dernière session acceptée reste exposée
 * ([rompreDerniereSession]) — le test de chaos simule la perte du socket
 * côté « app » (crash de l'app, mise à mort).
 */
@Suppress("SwallowedException")
internal class HoteSocketJvm(
    dossier: File,
) : HoteSocketTooling {
    private lateinit var canalServeur: ServerSocketChannel
    private val verrou = Mutex()

    override val cheminSocket: File = File(dossier, GradleProtocol.SOCKET_NAME)

    /** Dernier HelloRequest reçu du process réel. */
    var dernierHelloRecu: HelloRequest? = null
        private set

    /** Le secret présenté correspondait à celui passé au process. */
    var secretValide: Boolean = false
        private set

    /** Dernière session acceptée (le chaos rompt le canal côté « app »). */
    var derniereSession: SessionSocketJvm? = null
        private set

    /** Rompt la dernière session acceptée (§7.5 : socket perdue). */
    fun rompreDerniereSession() {
        derniereSession?.fermer()
    }

    override fun ouvrir() {
        cheminSocket.parentFile?.let { dossier -> if (!dossier.isDirectory) dossier.mkdirs() }
        cheminSocket.delete()
        canalServeur = ouvrirCanalUnix()
        canalServeur.bind(UnixDomainSocketAddress.of(cheminSocket.toPath()))
    }

    override suspend fun accepterUneFois(
        secretAttendu: String,
        delaiMs: Long,
    ): SessionTooling {
        val canal =
            withTimeout(delaiMs) {
                runInterruptible(Dispatchers.IO) { canalServeur.accept() }
            }
        canal.configureBlocking(true)

        // Première frame : obligatoirement le HelloRequest du process —
        // validé comme le ferait HandshakeApp (secret + version).
        val premiere = lireFrame(canal)
        val recu = ProtocolJson.decoderMessage(String(premiere, Charsets.UTF_8))
        check(recu is HelloRequest) { "première frame inattendue : ${recu::class.simpleName}" }
        dernierHelloRecu = recu
        secretValide = recu.handshakeSecret == secretAttendu
        check(secretValide) { "secret invalide — le process a présenté un autre secret" }
        check(recu.protocolVersion == GradleProtocol.PROTOCOL_VERSION) { "version incompatible" }

        val reponse =
            HelloResponse(
                id = recu.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                serverVersion = "app-test-e2e",
                gradleToolingApiVersion = "test",
                supportedFeatures = setOf("build", "sync", "tasks", "cancel", "heap"),
            )
        envoyerFrame(canal, reponse)
        return SessionSocketJvm(canal).also { session -> derniereSession = session }
    }

    override fun fermer() {
        runCatching { canalServeur.close() }
        cheminSocket.delete()
    }

    /**
     * `ServerSocketChannel.open(ProtocolFamily)` par réflexion.
     *
     * Le classpath de COMPILATION des tests unitaires Android porte
     * android.jar : pour les classes java.* couvertes par les builtins
     * Kotlin, c'est leur version JDK 8 qui gagne — la surcharge
     * `open(ProtocolFamily)` (JDK 15) n'y existe pas et ne résout PAS à la
     * compilation, alors même qu'elle est bien là au runtime (JDK 21).
     * Même précédent que la lecture réflexive du `pid` dans
     * `ProcessusGere` (core:bootstrap) : la réflexion court-circuite les
     * builtins et appelle la vraie méthode.
     */
    private fun ouvrirCanalUnix(): ServerSocketChannel {
        val methode = ServerSocketChannel::class.java.getMethod("open", java.net.ProtocolFamily::class.java)
        return methode.invoke(null, StandardProtocolFamily.UNIX) as ServerSocketChannel
    }

    private suspend fun envoyerFrame(
        canal: SocketChannel,
        message: ProtocolMessage,
    ) {
        withContext(Dispatchers.IO) {
            verrou.withLock {
                FrameCodec.writeFrame(
                    Channels.newOutputStream(canal),
                    ProtocolJson.encoder(message).encodeToByteArray(),
                )
            }
        }
    }

    private fun lireFrame(canal: SocketChannel): ByteArray {
        val entete = ByteBuffer.allocate(FrameCodec.TAILLE_ENTETE)
        lireEntierement(canal, entete)
        entete.flip()
        val taille = entete.int
        require(taille > 0 && taille <= GradleProtocol.MAX_FRAME_SIZE) { "taille de frame invalide : $taille" }
        val charge = ByteBuffer.allocate(taille)
        lireEntierement(canal, charge)
        return charge.array()
    }

    private fun lireEntierement(
        canal: SocketChannel,
        tampon: ByteBuffer,
    ) {
        while (tampon.hasRemaining()) {
            if (canal.read(tampon) < 0) throw EOFException("fin de flux en pleine frame")
        }
    }
}

/**
 * Session réelle côté JVM de test : miroir de `SessionSocketAndroid`
 * (écritures sérialisées par verrou, flux froid, EOF = complétion).
 */
@Suppress("SwallowedException")
internal class SessionSocketJvm(
    private val canal: SocketChannel,
) : SessionTooling {
    private val verrou = Mutex()

    override suspend fun envoyer(message: ProtocolMessage) {
        val charge = ProtocolJson.encoder(message).encodeToByteArray()
        withContext(Dispatchers.IO) {
            verrou.withLock {
                FrameCodec.writeFrame(Channels.newOutputStream(canal), charge)
            }
        }
    }

    override val evenements: Flow<ToolingEvent> =
        flow {
            try {
                while (true) {
                    val charge =
                        withContext(Dispatchers.IO) {
                            lireFrame(canal)
                        }
                    emit(ProtocolJson.decoderEvenement(String(charge, Charsets.UTF_8)))
                }
            } catch (fin: EOFException) {
                // Fin de session : le process a fermé le canal.
            } catch (fermee: IOException) {
                // Canal fermé (arrêt/démontage) : complétion, pas d'échec.
            }
        }

    override fun fermer() {
        runCatching { canal.close() }
    }

    private fun lireFrame(canal: SocketChannel): ByteArray {
        val entete = ByteBuffer.allocate(FrameCodec.TAILLE_ENTETE)
        lireEntierement(canal, entete)
        entete.flip()
        val taille = entete.int
        require(taille > 0 && taille <= GradleProtocol.MAX_FRAME_SIZE) { "taille de frame invalide : $taille" }
        val charge = ByteBuffer.allocate(taille)
        lireEntierement(canal, charge)
        return charge.array()
    }

    private fun lireEntierement(
        canal: SocketChannel,
        tampon: ByteBuffer,
    ) {
        while (tampon.hasRemaining()) {
            if (canal.read(tampon) < 0) throw EOFException("fin de flux en pleine frame")
        }
    }
}

/**
 * Lanceur RÉEL de sous-processus pour le bout-en-bout (§7.4) : miroir de
 * `LanceurProcessusNatifs` (core:bootstrap) en version test — le VRAI
 * process `java` démarre, ses sorties se lisent ligne à ligne, sa mort
 * s'attend par `onExit`.
 *
 * G6 (chaos §7.5) : les process lancés restent référencés — le test de
 * chaos tue le dernier ([tuerDernierProcess]) ou vérifie qu'aucun
 * orphelin ne survit à l'arrêt.
 */
internal class LanceurProcessusReel : NativeProcessLauncher {
    /** Process lancés, dans l'ordre (le chaos s'en sert). */
    val processus = CopyOnWriteArrayList<Process>()

    /** Tue le dernier process lancé (kill -9 si [force]). */
    fun tuerDernierProcess(force: Boolean) {
        processus.lastOrNull()?.let { process ->
            if (force) {
                process.destroyForcibly()
            } else {
                process.destroy()
            }
        }
    }

    /** Période de sonde de la mort du process (miroir du port : 100 ms). */
    private companion object {
        const val PERIODE_SONDE_MS = 100L
    }

    override fun launch(
        command: List<String>,
        extraEnv: Map<String, String>,
        workingDir: File?,
    ): ManagedProcess {
        val constructeur = ProcessBuilder(command)
        workingDir?.let { constructeur.directory(it) }
        val process = constructeur.start()
        processus += process
        return object : ManagedProcess {
            override val pid: Int =
                runCatching { process.pid().toInt() }.getOrDefault(-1)

            override fun isAlive(): Boolean = process.isAlive

            override fun stdoutLines(): Flow<String> = lignes(process.inputStream)

            override fun stderrLines(): Flow<String> = lignes(process.errorStream)

            override suspend fun awaitExit(): Int {
                // isAlive-polling (miroir exact du port production :
                // ProcessusGere sonde toutes les 100 ms — `onExit()` (JDK 9+)
                // ne résout pas à la compilation des tests unitaires Android,
                // voir ouvrirCanalUnix).
                while (process.isAlive) {
                    kotlinx.coroutines.delay(PERIODE_SONDE_MS)
                }
                return process.exitValue()
            }

            override fun kill(force: Boolean) {
                if (force) {
                    process.destroyForcibly()
                } else {
                    process.destroy()
                }
            }
        }
    }

    /** Flux froid des lignes d'un flux du process (miroir du port). */
    private fun lignes(flux: java.io.InputStream): Flow<String> =
        flow {
            val lecteur = java.io.BufferedReader(java.io.InputStreamReader(flux, Charsets.UTF_8))
            while (true) {
                val ligne = lecteur.readLine() ?: break
                emit(ligne)
            }
        }
}
