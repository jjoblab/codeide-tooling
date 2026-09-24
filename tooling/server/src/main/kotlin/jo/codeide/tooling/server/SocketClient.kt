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
 * Écritures : l'unique fil émetteur est le consommateur d'[EventBusSocket]
 * (plus le [Handshake] avant son démarrage) — jamais d'entrelacement de
 * frames ; [envoyer] reste tout de même synchronisé en défense en profondeur.
 */
internal class SocketClient private constructor(
    private val canal: SocketChannel,
) {
    private val entree: InputStream = Channels.newInputStream(canal)
    private val sortie: OutputStream = Channels.newOutputStream(canal)

    /** Écrit une frame complète (en-tête + payload). */
    @Synchronized
    fun envoyer(payload: ByteArray) {
        FrameSocket.ecrireFrame(sortie, payload)
    }

    /**
     * Lit une frame complète, bloquant. Lève [java.io.EOFException] sur une
     * fin de connexion propre (le dispatcher la distingue de la corruption,
     * §4.5) — les frames tronquées/corrompues lèvent leurs exceptions typées.
     */
    fun lireFrame(): ByteArray = FrameSocket.lireFrame(entree)

    /** Ferme le canal (idempotent). */
    fun fermer() {
        runCatching { canal.close() }
    }

    companion object {
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
