package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.BuildStarted
import jo.codeide.tooling.protocol.FrameCodec
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HelloRequest
import jo.codeide.tooling.protocol.HelloResponse
import jo.codeide.tooling.protocol.ProtocolJson
import jo.codeide.tooling.protocol.StreamKind
import jo.codeide.tooling.protocol.ToolingEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.reflect.KClass

/**
 * Banc « socket seul » (Phase 1, §3.1b) : l'orchestrateur COMPLET (vrai
 * handshake, vraie pile EventBus → SocketClient → FrameCodec) émet une
 * séquence de build SANS Gradle ; une app factice (miroir JVM du lecteur
 * Android) reçoit le cycle entier — ordre, intégralité des lignes,
 * alternance stdout/stderr.
 *
 * C'est la vérification JVM du banc : les mesures de PERFORMANCE (X1 à X5)
 * se font sur l'appareil de référence — aucune durée mesurée ici n'est un
 * résultat de performance.
 */
class BancSocketSeulTest {
    private val temporaire: Path = Files.createTempDirectory("banc-socket-seul")

    @After
    fun nettoyer() {
        temporaire.toFile().deleteRecursively()
        InstrumentationTransport.active = false
    }

    @Test
    fun `le banc emet le cycle complet dans l ordre sans perdre une ligne`() {
        val lignes = 25
        val app =
            AppBanc(
                cheminSocket = temporaire.resolve("gradle.sock"),
                secret = "s3cr3t-banc",
                arguments =
                    arrayOf(
                        "--socket",
                        temporaire.resolve("gradle.sock").toString(),
                        "--secret",
                        "s3cr3t-banc",
                        "--log-level",
                        "INFO",
                        "--heap-intervalle-ms",
                        "0",
                        "--instru-transport",
                        "--banc-socket-seul",
                        "--banc-lignes",
                        lignes.toString(),
                        "--banc-cadence-ms",
                        "1",
                        "--banc-longueur-ligne",
                        "100",
                        "--banc-contenu",
                        "utf8",
                    ),
            )
        app.demarrer()
        try {
            val demarrage = app.attendre(10_000, BuildStarted::class)
            assertEquals("banc-socket-seul", demarrage.buildId)
            val fin = app.attendre(15_000, BuildFinished::class)
            assertTrue(fin.succeeded)
            assertNotNull(fin.durationMs)

            // Intégralité et ordre : 25 lignes, toutes APRÈS le BuildStarted
            // et AVANT le BuildFinished du journal (indices du flux complet).
            val sorties = app.evenementsDe<BuildOutput>()
            assertEquals(lignes, sorties.size)
            val journal = app.journal
            val indiceDemarrage = journal.indexOf(demarrage)
            val indiceFin = journal.indexOf(fin)
            sorties.forEach { sortie ->
                val indice = journal.indexOf(sortie)
                assertTrue("ligne avant le BuildStarted", indice > indiceDemarrage)
                assertTrue("ligne après le BuildFinished", indice < indiceFin)
            }
            // Alternance stdout/stderr (moitié/moitié, l'indice PAIR est stdout).
            assertEquals(StreamKind.STDOUT, sorties[0].stream)
            assertEquals(StreamKind.STDERR, sorties[1].stream)
            // Longueur cible respectée : le mode UTF8 vise un QUART de la
            // longueur en caractères multi-octets (« banc 000000 café赵漢字… »),
            // soit ~40 des 100 demandés — le payload reste réaliste.
            assertTrue(sorties.all { it.line.length >= 40 })
            assertTrue(sorties.all { it.line.startsWith("banc ") })
            // Le buildId voyage sur CHAQUE ligne — la sonde de latence du
            // client (ConsoleLatence) peut s'y brancher sans adaptation.
            assertTrue(sorties.all { it.buildId == "banc-socket-seul" })
        } finally {
            app.fermer()
        }
    }

    @Test
    fun `le script metronome et son fichier d arret sont poses a cote du socket`() {
        val app =
            AppBanc(
                cheminSocket = temporaire.resolve("gradle2.sock"),
                secret = "s3cr3t-banc2",
                arguments =
                    arrayOf(
                        "--socket",
                        temporaire.resolve("gradle2.sock").toString(),
                        "--secret",
                        "s3cr3t-banc2",
                        "--heap-intervalle-ms",
                        "0",
                        "--instru-transport",
                        "--banc-socket-seul",
                        "--banc-lignes",
                        "3",
                    ),
            )
        app.demarrer()
        try {
            app.attendre(10_000, BuildFinished::class)
            assertTrue(Files.exists(temporaire.resolve(CoordonneesMetronome.NOM_SCRIPT_INIT)))
            assertTrue(Files.exists(temporaire.resolve(CoordonneesMetronome.NOM_FICHIER_ARRET)))
        } finally {
            app.fermer()
        }
    }

    // App factice du banc : écoute, handshake, journal des événements.
    // Exemption detekt ciblée : le fil lecteur vit pour s'arrêter sur EOF ou
    // démontage — même exemption que l'AppFactice de ServeurIntegrationTest.
    @Suppress("SwallowedException")
    private class AppBanc(
        cheminSocket: Path,
        private val secret: String,
        private val arguments: Array<String>,
    ) {
        private val canalServeur = ServerSocketChannel.open(StandardProtocolFamily.UNIX)

        private lateinit var canal: SocketChannel

        private val verrou = Any()

        /** Journal des événements reçus, dans l'ordre d'arrivée. */
        val journal = mutableListOf<ToolingEvent>()

        private lateinit var filOrchestrateur: Thread

        private lateinit var filLecture: Thread

        @Volatile
        var codeSortie: Int? = null

        init {
            Files.createDirectories(cheminSocket.parent)
            canalServeur.bind(UnixDomainSocketAddress.of(cheminSocket))
        }

        fun demarrer() {
            filOrchestrateur =
                thread(name = "orchestrateur-banc") {
                    codeSortie = runBlocking { ServerMain.executer(arguments) }
                }
            canal = canalServeur.accept()
            canal.configureBlocking(true)

            val hello = ProtocolJson.decoderMessage(String(lireFrame(), Charsets.UTF_8)) as HelloRequest
            assertEquals(secret, hello.handshakeSecret)
            envoyer(
                HelloResponse(
                    id = hello.id,
                    protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                    serverVersion = "app-banc",
                    gradleToolingApiVersion = "test",
                    supportedFeatures = setOf("build", "sync", "tasks", "cancel", "heap"),
                ),
            )

            filLecture =
                thread(isDaemon = true, name = "lecture-banc") {
                    try {
                        while (true) {
                            val evenement = ProtocolJson.decoderEvenement(String(lireFrame(), Charsets.UTF_8))
                            synchronized(verrou) { journal += evenement }
                        }
                    } catch (fin: EOFException) {
                        // Fin normale : l'orchestrateur ferme le canal.
                        // (Exemption detekt ciblée : ce fil lecteur vit pour
                        // s'arrêter sur l'une de ces deux fins — même
                        // exemption que l'AppFactice de ServeurIntegrationTest.)
                    } catch (fermee: Exception) {
                        // Démontage du test : fin normale aussi.
                    }
                }
        }

        /** Attend le premier événement du type demandé, échec net au délai. */
        fun <T : ToolingEvent> attendre(
            delaiMs: Long,
            type: KClass<T>,
        ): T {
            val debut = System.currentTimeMillis()
            while (System.currentTimeMillis() - debut < delaiMs) {
                synchronized(verrou) {
                    journal.filterIsInstance(type.java).firstOrNull()?.let { return it }
                }
                Thread.sleep(20)
            }
            throw AssertionError("aucun ${type.simpleName} en $delaiMs ms")
        }

        /** Événements du type demandé, dans l'ordre d'arrivée. */
        inline fun <reified T : ToolingEvent> evenementsDe(): List<T> =
            synchronized(verrou) { journal.filterIsInstance<T>() }

        fun fermer() {
            runCatching { canal.close() }
            runCatching { canalServeur.close() }
            filOrchestrateur.join(5_000)
            filLecture.join(1_000)
        }

        private fun envoyer(message: jo.codeide.tooling.protocol.ProtocolMessage) {
            FrameCodec.writeFrame(Channels.newOutputStream(canal), ProtocolJson.encoder(message).encodeToByteArray())
        }

        private fun lireFrame(): ByteArray = FrameCodec.readFrame(Channels.newInputStream(canal))
    }
}
