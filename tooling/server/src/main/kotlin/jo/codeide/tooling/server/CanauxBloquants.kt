package jo.codeide.tooling.server

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * Flux d'entrée et de sortie DIRECTS sur un [SocketChannel] bloquant — en
 * remplacement de `Channels.newInputStream`/`newOutputStream`.
 *
 * Constat (banc `BancSocketSeulTest` exécuté sur JDK 17, retour d'appareil
 * du 2026-11-07 en toile de fond) : les fabriques du JDK ≤ 20
 * s'interbloquent PAR CONSTRUCTION dès qu'un fil lit en bloquant pendant
 * qu'un autre écrit le MÊME canal —
 *
 * - la lecture tient `SelectableChannel.blockingLock()` pendant TOUTE la
 *   lecture native (`sun.nio.ch.ChannelInputStream.read(ch, bb, block)`) ;
 * - l'écriture attend ce MÊME verrou avant chaque octet
 *   (`java.nio.channels.Channels.writeFully`).
 *
 * Le dispatcher du serveur vit en lecture bloquante (l'app n'envoie rien
 * entre deux demandes) : il gèlerait le fil écrivain à sa PREMIÈRE frame
 * (« orchestrateur muet »). Le JDK 21 a réécrit ces flux sans verrou
 * partagé — un banc JDK 21 ne voit donc jamais le défaut.
 *
 * Ces adaptateurs répliquent le comportement du JDK 21 : lectures et
 * écritures par [SocketChannel.read]/[SocketChannel.write] directs, dont
 * les verrous d'implémentation sont SÉPARÉS par direction. Le contrat du
 * serveur est inchangé : un fil lecteur (handshake puis dispatcher), un
 * fil écrivain unique (ADR 0080) ; `close()` ferme le canal, comme les
 * fabriques du JDK. Sans état mutable partagé, ils sont sûrs pour ce
 * contrat par construction.
 */
internal object CanauxBloquants {
    /** Flux d'entrée direct sur [canal] (voir [CanauxBloquants]). */
    fun entree(canal: SocketChannel): InputStream = EntreeCanal(canal)

    /** Flux de sortie direct sur [canal] (voir [CanauxBloquants]). */
    fun sortie(canal: SocketChannel): OutputStream = SortieCanal(canal)

    /**
     * Lecture : alloue ses tampons localement (aucun état partagé) — la
     * fréquence de lecture est celle des DEMANDES de l'app, l'allocation
     * d'un octet par lecture d'en-tête est négligeable.
     *
     * Exemption detekt ciblée (règle 16) : MagicNumber — le masque 0xFF
     * est la conversion octet signé → non signé canonique, même
     * justification que `FrameCodec.writeFrame`.
     */
    @Suppress("MagicNumber")
    private class EntreeCanal(
        private val canal: SocketChannel,
    ) : InputStream() {
        override fun read(): Int {
            val octet = ByteBuffer.allocate(1)
            return if (canal.read(octet) < 0) {
                -1
            } else {
                octet.flip().get().toInt() and 0xFF
            }
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            return canal.read(ByteBuffer.wrap(b, off, len))
        }

        override fun close() {
            canal.close()
        }
    }

    /** Écriture : tampons locaux, boucle de vidage par sécurité. */
    private class SortieCanal(
        private val canal: SocketChannel,
    ) : OutputStream() {
        override fun write(b: Int) {
            val tampon = ByteBuffer.allocate(1)
            tampon.put(b.toByte()).flip()
            while (tampon.hasRemaining()) canal.write(tampon)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (len == 0) return
            val tampon = ByteBuffer.wrap(b, off, len)
            while (tampon.hasRemaining()) canal.write(tampon)
        }

        override fun close() {
            canal.close()
        }
    }
}
