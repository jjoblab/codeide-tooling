package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.FrameCodec
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Client du socket Unix de l'app (§4.1) : l'orchestrateur EST le client —
 * l'app a ouvert l'écoute AVANT de le lancer, il s'y connecte dès son
 * démarrage (aucun fichier de découverte, aucun polling).
 *
 * JDK 16+ : `UnixDomainSocketAddress`/`SocketChannel` standards, aucune
 * dépendance tierce (§4.1) — c'est aussi ce qui permet de tester ce module
 * sans Android (§7.2). Les canaux NIO implémentent `InterruptibleChannel` :
 * la connexion bloquante s'interrompt proprement à l'annulation de la
 * coroutine (`runInterruptible`), le délai est porté par [connecter].
 *
 * Écritures (v0.49.0, ADR 0080) : UN fil écrivain unique consomme une file
 * FIFO bornée — plus JAMAIS d'écriture directe concurrente une fois
 * [demarrerEcrivain] lancé. Avant ce démarrage (handshake), les écritures
 * restent directes sous verrou : elles viennent du fil principal, seules
 * et séquentielles. Le PONG dispose d'une file PRIORITAIRE distincte
 * (débit nul : un ping toutes les 5 s) : la réponse de santé ne peut plus
 * être ensevelie sous des frames d'événements, NI attendre un verrou tenu
 * par une écriture bloquée (le correctif v0.37.3 écrivait le pong hors bus
 * mais TOUJOURS sous le moniteur partagé de [envoyer] : une écriture
 * d'événement bloquée sur un tampon de réception plein de l'app gardait le
 * moniteur et ensevelissait le pong quand même — « orchestrateur muet »
 * en plein build, retour terrain v0.48.0). L'ordre RELATIF pong/événements
 * peut s'inverser, sans conséquence (le pong ne porte aucune relation
 * d'ordre, cf. v0.37.3).
 */
internal class SocketClient private constructor(
    private val canal: SocketChannel,
) {
    private val entree: InputStream = Channels.newInputStream(canal)
    private val sortie: OutputStream = Channels.newOutputStream(canal)

    /**
     * File FIFO des frames normales (événements, réponses d'erreur) :
     * bornée, [envoyer] BLOQUE sous contre-pression — la sémantique
     * sans perte de l'[EventBus] est conservée de bout en bout.
     */
    private val fileNormale = ArrayBlockingQueue<ByteArray>(CAPACITE_FILE)

    /**
     * File prioritaire des frames de SANTÉ (pong) : non bornée par
     * construction (débit nul — un ping toutes les 5 s au plus), elle ne
     * peut ni déborder la mémoire ni retarder le pong.
     */
    private val filePrioritaire = ConcurrentLinkedDeque<ByteArray>()

    @Volatile
    private var ecrivain: Thread? = null

    @Volatile
    private var actif = false

    /** Datation du dernier avertissement de profondeur (bornage du débit). */
    private val dernierAvertissementMs = AtomicLong(0L)

    /** Écrit une frame complète (en-tête + payload). */
    fun envoyer(payload: ByteArray) {
        if (ecrivain == null) {
            // Phase de handshake : fil principal, écritures séquentielles.
            synchronized(this) { FrameSocket.ecrireFrame(sortie, payload) }
        } else {
            // put() bloquant : contre-pression vers l'émetteur, aucune perte.
            fileNormale.put(payload)
        }
    }

    /**
     * Écrit une frame de santé (pong) : TOUJOURS prioritaire sur les
     * frames normales — elle ne patiente JAMAIS derrière la file
     * d'événements, même sous rafale de sortie de build.
     */
    fun envoyerPrioritaire(payload: ByteArray) {
        if (ecrivain == null) {
            synchronized(this) { FrameSocket.ecrireFrame(sortie, payload) }
        } else {
            filePrioritaire.addLast(payload)
        }
    }

    /**
     * Démarre le fil écrivain unique (idempotent) — appelé par
     * [ServerMain] une fois le handshake négocié : à partir de là, toute
     * frame passe par sa file, plus aucune écriture directe.
     */
    fun demarrerEcrivain() {
        if (ecrivain?.isAlive == true) return
        actif = true
        ecrivain =
            thread(isDaemon = true, name = "gradle-server-ecrivain") {
                try {
                    while (actif || fileNormale.isNotEmpty() || filePrioritaire.isNotEmpty()) {
                        // Priorité santé d'abord : un pong en attente passe
                        // devant TOUT événement en file.
                        val frame =
                            filePrioritaire.pollFirst()
                                ?: fileNormale.poll(INTERVALLE_MS, TimeUnit.MILLISECONDS)
                                ?: continue
                        FrameSocket.ecrireFrame(sortie, frame)
                        // v0.49.0 (diagnostic latence) : la profondeur de
                        // la file normale nomme le retard d'écriture vers
                        // l'app — c'est le seul endroit où il se mesure.
                        avertirSiProfondeur()
                    }
                } catch (interruption: InterruptedException) {
                    // arrêt demandé : vider ce qui reste, au mieux.
                    while (true) {
                        val frame = filePrioritaire.pollFirst() ?: fileNormale.poll() ?: break
                        runCatching { FrameSocket.ecrireFrame(sortie, frame) }
                    }
                } catch (t: Throwable) {
                    Journal.error("écrivain du socket interrompu : ${t.message}", t)
                }
            }
    }

    /**
     * Avertit (borné en débit) quand la file normale s'accumule : la
     * profondeur croissante signale une écriture bloquée vers l'app —
     * l'opérateur voit le retard SE FORMER au lieu de le découvrir à la
     * fin du build.
     */
    private fun avertirSiProfondeur() {
        val profondeur = fileNormale.size
        if (profondeur >= SEUIL_ALERTE_FILE) {
            val maintenant = System.currentTimeMillis()
            val dernier = dernierAvertissementMs.get()
            if (maintenant - dernier >= INTERVALLE_ALERTE_MS &&
                dernierAvertissementMs.compareAndSet(dernier, maintenant)
            ) {
                Journal.warn("file d'écriture socket à $profondeur frame(s) — l'app lit en retard")
            }
        }
    }

    /**
     * Lit une frame complète, bloquant. Lève [java.io.EOFException] sur une
     * fin de connexion propre (le dispatcher la distingue de la corruption,
     * §4.5) — les frames tronquées/corrompues lèvent leurs exceptions typées.
     */
    fun lireFrame(): ByteArray = FrameSocket.lireFrame(entree)

    /**
     * Referme le canal (idempotent) — l'écrivain reçoit d'abord une fenêtre
     * bornée pour vider les files (aucune perte évitable), puis le canal se
     * ferme : ce qui n'a pas passé est perdu avec la connexion, comme
     * toujours à la rupture.
     */
    fun fermer() {
        arreterEcrivain()
        runCatching { canal.close() }
    }

    /** Fenêtre bornée de vidange de l'écrivain (idempotent). */
    private fun arreterEcrivain() {
        actif = false
        ecrivain?.interrupt()
        ecrivain?.join(ATTENTE_JOINTURE_MS)
        ecrivain = null
    }

    companion object {
        /**
         * Capacité de la file normale (v0.49.0) : reprend la capacité de
         * l'EventBus (8192) — la contre-pression naît au même ordre de
         * grandeur qu'avant le correctif, jamais plus tôt.
         */
        private const val CAPACITE_FILE = 8192

        /** Attente max d'une frame avant de réévaluer l'état d'activité. */
        private const val INTERVALLE_MS = 100L

        /** Attente max de fin de l'écrivain à la fermeture. */
        private const val ATTENTE_JOINTURE_MS = 2_000L

        /** Profondeur au-delà de laquelle la file normale est signalée. */
        private const val SEUIL_ALERTE_FILE = 512

        /** Bornage du débit des avertissements de profondeur (5 s). */
        private const val INTERVALLE_ALERTE_MS = 5_000L

        /**
         * Connecte au socket [chemin] dans les [delaiMs] (§3.4 :
         * `CONNECT_TIMEOUT_MS`). Au délai, le canal est fermé — un canal NIO
         * interrompu/fermé débloque le thread de connexion.
         */
        suspend fun connecter(
            chemin: Path,
            delaiMs: Long,
        ): SocketClient {
            val canal = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                withTimeout(delaiMs) {
                    runInterruptible {
                        canal.configureBlocking(true)
                        canal.connect(UnixDomainSocketAddress.of(chemin))
                    }
                }
                return SocketClient(canal)
            } catch (t: Throwable) {
                runCatching { canal.close() }
                throw IOException("Connexion impossible à $chemin dans les $delaiMs ms : ${t.message}", t)
            }
        }
    }
}

/** Framing (§3.1) vu du serveur : délègue au [FrameCodec] du protocole. */
internal object FrameSocket {
    fun ecrireFrame(
        sortie: OutputStream,
        payload: ByteArray,
    ) {
        FrameCodec.writeFrame(sortie, payload)
    }

    fun lireFrame(entree: InputStream): ByteArray = FrameCodec.readFrame(entree)
}
