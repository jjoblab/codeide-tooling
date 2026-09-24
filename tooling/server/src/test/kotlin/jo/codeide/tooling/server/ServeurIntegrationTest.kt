package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.BuildRequest
import jo.codeide.tooling.protocol.CancelRequest
import jo.codeide.tooling.protocol.DependenciesRequest
import jo.codeide.tooling.protocol.DependenciesResult
import jo.codeide.tooling.protocol.ErrorCode
import jo.codeide.tooling.protocol.ErrorResponse
import jo.codeide.tooling.protocol.FrameCodec
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HeapEvent
import jo.codeide.tooling.protocol.HeapRequest
import jo.codeide.tooling.protocol.HelloRequest
import jo.codeide.tooling.protocol.HelloResponse
import jo.codeide.tooling.protocol.ModelRequest
import jo.codeide.tooling.protocol.PingMessage
import jo.codeide.tooling.protocol.PongMessage
import jo.codeide.tooling.protocol.ProtocolJson
import jo.codeide.tooling.protocol.ProtocolMessage
import jo.codeide.tooling.protocol.SyncRequest
import jo.codeide.tooling.protocol.SyncResult
import jo.codeide.tooling.protocol.TaskStarted
import jo.codeide.tooling.protocol.TasksRequest
import jo.codeide.tooling.protocol.TasksResult
import jo.codeide.tooling.protocol.ToolingEvent
import jo.codeide.tooling.protocol.ToolingRequest
import jo.codeide.tooling.testing.FixturesGradle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.reflect.KClass

/**
 * Tests d'intégration RÉELS du serveur (§7.2) : l'orchestrateur complet tourne
 * dans la JVM de test, connecté par un vrai socket Unix à une app factice,
 * et exécute de VRAIS builds Gradle contre les fixtures de tooling:testing
 * (dont les vrais daemons Gradle) — le principal filet de sécurité du
 * module, sans Android.
 *
 * Chaque test repart d'un serveur et d'une fixture vierges (copiée en
 * temporaire, jamais construite en place). Le premier test qui touche une
 * fixture amorce le daemon Gradle (~15 s une seule fois), les suivants le
 * réutilisent.
 */
class ServeurIntegrationTest {
    private val temporaires = mutableListOf<Path>()
    private var app: AppFactice? = null

    @After
    fun nettoyer() {
        app?.fermer()
        app = null
        temporaires.forEach { it.toFile().deleteRecursively() }
        temporaires.clear()
    }

    private fun dossierFrais(): Path {
        val dossier = FixturesGradle.dossierTemporaire()
        temporaires.add(dossier)
        return dossier
    }

    private fun fixture(nom: String): Path = FixturesGradle.copier(nom, dossierFrais())

    /** Démarre l'app factice + l'orchestrateur, handshake validé. */
    private fun demarrer(
        secret: String = SECRET,
        reponseHandshake: ((HelloRequest) -> ProtocolMessage)? = null,
    ): AppFactice {
        val dossierSocket = dossierFrais()
        val cheminSocket = dossierSocket.resolve(GradleProtocol.SOCKET_NAME)
        AppFactice(cheminSocket, secret, reponseHandshake).also {
            it.demarrer()
            app = it
            return it
        }
    }

    // ------------------------------------------------------------------
    // Handshake (§4.4, §1.6).
    // ------------------------------------------------------------------

    @Test
    fun `le handshake porte le secret, la version et l'identité de l'orchestrateur`() {
        val app = demarrer()
        assertEquals(SECRET, app.helloRecu.handshakeSecret)
        assertEquals(GradleProtocol.PROTOCOL_VERSION, app.helloRecu.protocolVersion)
        assertEquals(ServerVersion.CURRENT, app.helloRecu.clientVersion)
    }

    @Test
    fun `une version de protocole incompatible produit une erreur claire et l'arrêt`() {
        var codeSortie: Int? = null
        val app =
            demarrer(
                reponseHandshake = { bonjour ->
                    HelloResponse(
                        id = bonjour.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION - 1,
                        serverVersion = "app-test",
                        gradleToolingApiVersion = "test",
                        supportedFeatures = emptySet(),
                    )
                },
            )
        val erreur = app.attendre(10_000, ErrorResponse::class)
        assertEquals(ErrorCode.PROTOCOL_VERSION_MISMATCH, erreur.code)
        app.attendreArret(10_000)
        codeSortie = app.codeSortie
        assertEquals(Handshake.CODE_VERSION, codeSortie)
    }

    @Test
    fun `un refus de l'app arrête l'orchestrateur avec le code dédié`() {
        val app =
            demarrer(
                reponseHandshake = { bonjour ->
                    jo.codeide.tooling.protocol.ErrorResponse(
                        id = bonjour.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        requestId = bonjour.id,
                        code = ErrorCode.HANDSHAKE_FAILED,
                        message = "secret invalide",
                    )
                },
            )
        app.attendreArret(10_000)
        assertEquals(Handshake.CODE_REFUS, app.codeSortie)
    }

    // ------------------------------------------------------------------
    // Builds réels (§7.2 : minimal, erreur, multi-module, annulation).
    // ------------------------------------------------------------------

    @Test
    fun `un build minimal reussit et diffuse sa sortie ligne a ligne`() {
        val app = demarrer()
        val projet = fixture("minimal-java")
        app.envoyer(
            BuildRequest(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projet.toString(),
                tasks = listOf("saluer"),
                buildId = "b-minimal",
            ),
        )
        val fin = app.attendre(DELAI_BUILD, BuildFinished::class)
        assertTrue("le build devait réussir : ${fin.failureMessage}", fin.succeeded)
        assertEquals("b-minimal", fin.buildId)
        assertTrue(fin.durationMs > 0)
        assertTrue(
            "la sortie devait contenir le salut de la fixture",
            app.sortiesContenant("Bonjour depuis minimal-java").isNotEmpty(),
        )
    }

    @Test
    fun `un build en erreur de compilation echoue avec un message utile`() {
        val app = demarrer()
        val projet = fixture("erreur-compilation")
        app.envoyer(
            BuildRequest(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projet.toString(),
                tasks = listOf("compileJava"),
                buildId = "b-casse",
            ),
        )
        val fin = app.attendre(DELAI_BUILD, BuildFinished::class)
        assertEquals(false, fin.succeeded)
        assertNotNull("un message d'échec était attendu", fin.failureMessage)
    }

    @Test
    fun `un build multi-modules relie les projets par taches qualifiees`() {
        val app = demarrer()
        val projet = fixture("multi-module")
        app.envoyer(
            BuildRequest(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projet.toString(),
                tasks = listOf(":app:executer"),
                buildId = "b-multi",
            ),
        )
        val fin = app.attendre(DELAI_BUILD, BuildFinished::class)
        assertTrue("le build multi-module devait réussir : ${fin.failureMessage}", fin.succeeded)
        assertTrue(
            "la sortie de la tâche :app:executer devait passer",
            app.sortiesContenant("app exécuté avec lib").isNotEmpty(),
        )
    }

    @Test
    fun `l annulation arrete un build long avant son terme`() {
        val app = demarrer()
        val projet = fixture("tache-longue")
        val debut = System.currentTimeMillis()
        app.envoyer(
            BuildRequest(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projet.toString(),
                tasks = listOf("endormir"),
                arguments = listOf("-PdureeMs=60000"),
                buildId = "b-long",
            ),
        )
        app.attendre(DELAI_BUILD, TaskStarted::class)
        Thread.sleep(500)
        app.envoyer(
            CancelRequest(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                buildId = "b-long",
            ),
        )
        val fin = app.attendre(DELAI_BUILD, BuildFinished::class)
        assertEquals(false, fin.succeeded)
        assertTrue(
            "l'annulation devait arrêter le build bien avant les 60 s (durée : ${fin.durationMs} ms)",
            fin.durationMs < 55_000,
        )
        assertTrue(
            "toute l'annulation devait prendre moins de 55 s (pris : ${System.currentTimeMillis() - debut} ms)",
            System.currentTimeMillis() - debut < 55_000,
        )
    }

    // ------------------------------------------------------------------
    // Modèles : tâches, synchronisation, dépendances (§5.3).
    // ------------------------------------------------------------------

    @Test
    fun `la liste des taches expose celles de la fixture`() {
        val app = demarrer()
        val projet = fixture("minimal-java")
        app.envoyer(
            TasksRequest(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projet.toString(),
            ),
        )
        val resultat = app.attendre(DELAI_BUILD, TasksResult::class)
        assertTrue(
            "la tâche saluer devait être listée : ${resultat.tasks.joinToString { it.path }}",
            resultat.tasks.any { it.path.endsWith("saluer") },
        )
    }

    @Test
    fun `la synchronisation resout les modeles du projet`() {
        val app = demarrer()
        val projet = fixture("minimal-java")
        app.envoyer(
            SyncRequest(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projet.toString(),
            ),
        )
        val resultat = app.attendre(DELAI_BUILD, SyncResult::class)
        assertTrue("la synchronisation devait réussir : ${resultat.failureMessage}", resultat.succeeded)
    }

    @Test
    fun `les dependances listent les modules relies`() {
        val app = demarrer()
        val projet = fixture("multi-module")
        app.envoyer(
            DependenciesRequest(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projet.toString(),
            ),
        )
        val resultat = app.attendre(DELAI_BUILD, DependenciesResult::class)
        assertTrue(
            "la dépendance app → lib devait apparaître : ${resultat.dependencies.joinToString { it.module }}",
            resultat.dependencies.any { it.module == "lib" || it.module == ":lib" },
        )
    }

    @Test
    fun `la resolution de modele repond par un SyncResult reussi`() {
        val app = demarrer()
        val projet = fixture("minimal-java")
        app.envoyer(
            ModelRequest(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projet.toString(),
            ),
        )
        val resultat = app.attendre(DELAI_BUILD, SyncResult::class)
        assertTrue("le modèle devait se résoudre : ${resultat.failureMessage}", resultat.succeeded)
    }

    // ------------------------------------------------------------------
    // Santé et robustesse (§7.5).
    // ------------------------------------------------------------------

    @Test
    fun `le ping repond pong avec le meme identifiant`() {
        val app = demarrer()
        val identifiant = nouvelId()
        app.envoyer(
            PingMessage(
                id = identifiant,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
            ),
        )
        val pong = app.attendre(10_000, PongMessage::class)
        assertEquals(identifiant, pong.id)
    }

    @Test
    fun `une demande de tas repond un instantane coherent`() {
        val app = demarrer()
        app.envoyer(
            HeapRequest(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
            ),
        )
        val tas = app.attendre(10_000, HeapEvent::class)
        assertTrue("le tas utilisé devait être positif : ${tas.usedMb}", tas.usedMb > 0)
        assertTrue("le tas maximum devait être positif : ${tas.maxMb}", tas.maxMb > 0)
    }

    @Test
    fun `une requete inconnue renvoie une erreur typée sans couper la session`() {
        val app = demarrer()
        app.envoyerBrut("""{"type":"cette_requete_n_existe_pas","id":"x1","protocolVersion":2}""")
        val erreur = app.attendre(10_000, ErrorResponse::class)
        assertEquals(ErrorCode.UNKNOWN_REQUEST, erreur.code)
        // La session survit : un ping passe encore.
        app.envoyer(
            PingMessage(id = nouvelId(), protocolVersion = GradleProtocol.PROTOCOL_VERSION),
        )
        assertNotNull(app.attendre(10_000, PongMessage::class))
    }

    @Test
    fun `la fermeture de l'app termine l'orchestrateur proprement (code 0)`() {
        val app = demarrer()
        app.fermerCanal()
        app.attendreArret(15_000)
        assertEquals(0, app.codeSortie)
    }

    private companion object {
        const val SECRET = "secret-de-test-integration"

        /** Délai des attentes de build (premier daemon Gradle ~15 s). */
        const val DELAI_BUILD: Long = 180_000

        fun nouvelId(): String = UUID.randomUUID().toString()
    }
}

// Exemption detekt ciblée (règle 16) : SwallowedException — le fil lecteur
// d'AppFactice se termine par construction sur une fin de flux (EOF) ou une
// fermeture de canal (démontage du test) : ces exceptions SONT le signal de
// fin attendu, les remonter ferait échouer des tests verts.
@Suppress("SwallowedException")
/** L'app factice (serveur du socket côté test) et ses observations. */
private class AppFactice(
    private val cheminSocket: Path,
    private val secret: String,
    private val reponseHandshake: ((HelloRequest) -> ProtocolMessage)?,
) {
    private lateinit var canalServeur: ServerSocketChannel
    private lateinit var canal: SocketChannel
    private val verrou = Any()

    /** Journal des événements reçus de l'orchestrateur, dans l'ordre. */
    val journal = mutableListOf<ToolingEvent>()

    /** Le HelloRequest reçu (secret, version, identité). */
    lateinit var helloRecu: HelloRequest

    @Volatile
    var codeSortie: Int? = null

    private lateinit var filOrchestrateur: Thread
    private lateinit var filLecture: Thread

    fun demarrer() {
        Files.createDirectories(cheminSocket.parent)
        canalServeur = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        canalServeur.bind(UnixDomainSocketAddress.of(cheminSocket))

        filOrchestrateur =
            thread(name = "orchestrateur-${cheminSocket.fileName}") {
                codeSortie =
                    runBlocking {
                        ServerMain.executer(
                            arrayOf(
                                "--socket",
                                cheminSocket.toString(),
                                "--secret",
                                secret,
                                "--log-level",
                                "INFO",
                                "--heap-intervalle-ms",
                                "0",
                            ),
                        )
                    }
            }

        // L'app accepte la connexion entrante de l'orchestrateur (client).
        canal = canalServeur.accept()
        canal.configureBlocking(true)

        // Handshake : première frame lue en direct, réponse envoyée, puis
        // seulement le fil de lecture continue pour le reste de la session.
        helloRecu = ProtocolJson.decoderMessage(String(lireFrame(), Charsets.UTF_8)) as HelloRequest
        val reponse =
            reponseHandshake?.invoke(helloRecu)
                ?: HelloResponse(
                    id = helloRecu.id,
                    protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                    serverVersion = "app-test",
                    gradleToolingApiVersion = "test",
                    supportedFeatures = setOf("build", "sync", "tasks", "cancel", "heap"),
                )
        envoyerMessage(reponse)

        filLecture =
            thread(isDaemon = true, name = "lecture-${cheminSocket.fileName}") {
                try {
                    while (true) {
                        val evenement =
                            ProtocolJson.decoderEvenement(String(lireFrame(), Charsets.UTF_8))
                        synchronized(verrou) { journal += evenement }
                    }
                } catch (fin: EOFException) {
                    // Fin NORMALE de session : l'orchestrateur s'arrête en
                    // fermant le canal — rien à signaler, le fil sort.
                    // (Exemption detekt ciblée : ce fil lecteur vit pour
                    // s'arrêter sur l'une de ces deux fins.)
                } catch (fermee: Exception) {
                    // Canal fermé par le démontage du test : idem, fin
                    // normale — l'exception n'est pas une erreur de test.
                }
            }
    }

    /** Envoie une requête à l'orchestrateur. */
    fun envoyer(requete: ToolingRequest) {
        envoyerMessage(requete)
    }

    /** Envoie un payload BRUT (messages invalides, §7.5). */
    fun envoyerBrut(payload: String) {
        FrameCodec.writeFrame(Channels.newOutputStream(canal), payload.encodeToByteArray())
    }

    /** Attend le premier événement du type demandé, échec net au délai. */
    fun <T : ToolingEvent> attendre(
        delaiMs: Long,
        type: KClass<T>,
    ): T {
        val debut = System.currentTimeMillis()
        var curseur = 0
        while (System.currentTimeMillis() - debut < delaiMs) {
            synchronized(verrou) {
                while (curseur < journal.size) {
                    val evenement = journal[curseur]
                    curseur++
                    if (type.isInstance(evenement)) return type.java.cast(evenement)
                }
            }
            Thread.sleep(20)
        }
        throw AssertionError(
            "aucun ${type.simpleName} en $delaiMs ms — reçu : " +
                synchronized(verrou) { journal.joinToString(limit = 25) { it::class.simpleName ?: "?" } },
        )
    }

    /** Lignes de sortie de build contenant [texte]. */
    fun sortiesContenant(texte: String): List<BuildOutput> =
        synchronized(verrou) {
            journal.filterIsInstance<BuildOutput>().filter { it.line.contains(texte) }
        }

    /** Attend que l'orchestrateur rende sa main (code de sortie). */
    fun attendreArret(delaiMs: Long) {
        val debut = System.currentTimeMillis()
        while (codeSortie == null && System.currentTimeMillis() - debut < delaiMs) {
            Thread.sleep(20)
        }
        assertNotNull("l'orchestrateur devait s'arrêter (code de sortie)", codeSortie)
    }

    /** Ferme le canal côté app : l'orchestrateur voit une fin de connexion propre. */
    fun fermerCanal() {
        runCatching { canal.close() }
    }

    /** Démontage complet du test. */
    fun fermer() {
        fermerCanal()
        filOrchestrateur.join(20_000)
        runCatching { canalServeur.close() }
        if (this::filLecture.isInitialized) filLecture.join(2_000)
    }

    private fun envoyerMessage(message: ProtocolMessage) {
        FrameCodec.writeFrame(
            Channels.newOutputStream(canal),
            ProtocolJson.encoder(message).encodeToByteArray(),
        )
    }

    private fun lireFrame(): ByteArray {
        val entete = ByteBuffer.allocate(FrameCodec.TAILLE_ENTETE)
        lireEntierement(entete)
        entete.flip()
        val taille = entete.int
        require(taille > 0 && taille <= GradleProtocol.MAX_FRAME_SIZE) { "taille de frame invalide : $taille" }
        val payload = ByteBuffer.allocate(taille)
        lireEntierement(payload)
        return payload.array()
    }

    private fun lireEntierement(tampon: ByteBuffer) {
        while (tampon.hasRemaining()) {
            if (canal.read(tampon) < 0) throw EOFException("fin de flux en pleine frame")
        }
    }
}
