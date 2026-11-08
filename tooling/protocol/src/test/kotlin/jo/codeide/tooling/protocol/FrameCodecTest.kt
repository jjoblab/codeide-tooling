package jo.codeide.tooling.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * Tests du framing (§3.1/§7.1) : frame valide, annoncée trop grande
 * (garde DoS — rejet **avant** allocation), tronquée (en-tête incomplet
 * ou payload coupé), longueur invalide, borne exacte.
 */
class FrameCodecTest {
    /** Entête 4 octets gros-boutiste d'une longueur. */
    private fun entete(longueur: Long): ByteArray =
        byteArrayOf(
            (longueur shr 24).toByte(),
            (longueur shr 16).toByte(),
            (longueur shr 8).toByte(),
            longueur.toByte(),
        )

    @Test
    fun `round-trip d une frame valide`() {
        val flux = ByteArrayOutputStream()
        val payload = byteArrayOf(1, 2, 3, 42, 0, -1)

        FrameCodec.writeFrame(flux, payload)

        val relue = FrameCodec.readFrame(ByteArrayInputStream(flux.toByteArray()))
        assertArrayEquals(payload, relue)
    }

    @Test
    fun `deux frames consecutives se lisent dans l ordre`() {
        val flux = ByteArrayOutputStream()
        FrameCodec.writeFrame(flux, byteArrayOf(1))
        FrameCodec.writeFrame(flux, byteArrayOf(2, 2))

        val entree = ByteArrayInputStream(flux.toByteArray())
        assertArrayEquals(byteArrayOf(1), FrameCodec.readFrame(entree))
        assertArrayEquals(byteArrayOf(2, 2), FrameCodec.readFrame(entree))
    }

    @Test
    fun `payload vide refuse a l ecriture - longueur zero refusee a la lecture`() {
        // Un message JSON fait au moins `{}` : écrire un payload vide est
        // une erreur de programmation, pas un cas câble.
        val flux = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.writeFrame(flux, ByteArray(0))
        }

        // Et une longueur NULLE annoncée par un pair fautif est rejetée
        // sans allocation.
        val buggue = ByteArrayInputStream(entete(0))
        assertThrows(FrameInvalideException::class.java) { FrameCodec.readFrame(buggue) }
    }

    @Test
    fun `frame annoncee trop grande rejetee avant allocation`() {
        val taille = GradleProtocol.MAX_FRAME_SIZE + 1
        val entree = ByteArrayInputStream(entete(taille.toLong()))

        // Le garde DoS (§3.1) doit rejeter sans lire ni allouer 16 Mo.
        val exception =
            assertThrows(FrameTropGrandeException::class.java) {
                FrameCodec.readFrame(entree)
            }
        assertEquals(true, exception.message?.contains(taille.toString()))
    }

    @Test
    fun `entete tronquee - deux octets puis fin de flux`() {
        val entree = ByteArrayInputStream(byteArrayOf(0, 0))

        assertThrows(FrameTronqueeException::class.java) {
            FrameCodec.readFrame(entree)
        }
    }

    @Test
    fun `payload tronque - longueur annoncee superieure au disponible`() {
        val annonce = 10L
        val entree = ByteArrayInputStream(entete(annonce) + byteArrayOf(1, 2, 3))

        val exception =
            assertThrows(FrameTronqueeException::class.java) {
                FrameCodec.readFrame(entree)
            }
        // Le diagnostic dit exactement combien d'octets étaient arrivés.
        assertEquals(true, exception.message?.contains("3 octets lus sur 10"))
    }

    @Test
    fun `longueur negative (0xFFFFFFFF) refusee`() {
        val entree = ByteArrayInputStream(entete(0xFFFFFFFFL))

        assertThrows(FrameInvalideException::class.java) {
            FrameCodec.readFrame(entree)
        }
    }

    @Test
    fun `borne exacte - MAX_FRAME_SIZE accepte`() {
        val payload = ByteArray(GradleProtocol.MAX_FRAME_SIZE) { 7 }
        val flux = ByteArrayOutputStream()

        FrameCodec.writeFrame(flux, payload)

        val relue = FrameCodec.readFrame(ByteArrayInputStream(flux.toByteArray()))
        assertEquals(GradleProtocol.MAX_FRAME_SIZE, relue.size)
    }

    @Test
    fun `ecrire un payload trop grand est refuse par precondition`() {
        val flux = ByteArrayOutputStream()
        val payload = ByteArray(GradleProtocol.MAX_FRAME_SIZE + 1)

        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.writeFrame(flux, payload)
        }
    }

    @Test
    fun `EOF propre sur un flux vide`() {
        // Un flux immédiatement fermé n'est PAS une frame tronquée : le
        // appelant traite la fin de connexion (EOF) lui-même — le codec
        // la laisse passer telle quelle.
        assertThrows(EOFException::class.java) {
            FrameCodec.readFrame(ByteArrayInputStream(ByteArray(0)))
        }
    }

    // ---- T-F1 (Phase 2) : écriture de frame en UN appel -----------------

    /** Compteur d'appels `write` — prouve le regroupement, pas juste l'octet. */
    private class FluxCompteur : java.io.OutputStream() {
        var appels = 0
        val octets = mutableListOf<Byte>()

        override fun write(octet: Int) {
            appels++
            octets += octet.toByte()
        }

        override fun write(
            octets: ByteArray,
            depart: Int,
            longueur: Int,
        ) {
            appels++
            for (i in depart until depart + longueur) {
                this.octets += octets[i]
            }
        }

        fun tableau(): ByteArray = octets.toByteArray()
    }

    @Test
    fun `T-F1 - une frame s ecrit en un seul appel write`() {
        val flux = FluxCompteur()

        FrameCodec.writeFrame(flux, byteArrayOf(1, 2, 3))

        // Un appel d'écriture (+ le flush, hors compteur) : l'en-tête et
        // le payload partent ENSEMBLE — contre quatre appels + payload
        // avant T-F1.
        assertEquals(1, flux.appels)
    }

    @Test
    fun `T-F1 - les octets du fil sont inchanges par l ecriture groupee`() {
        val ancien = ByteArrayOutputStream()
        FrameCodec.writeFrame(ancien, byteArrayOf(1, 2, 3))
        FrameCodec.writeFrame(ancien, byteArrayOf(42))

        val nouveau = FluxCompteur()
        FrameCodec.writeFrame(nouveau, byteArrayOf(1, 2, 3))
        FrameCodec.writeFrame(nouveau, byteArrayOf(42))

        assertArrayEquals(ancien.toByteArray(), nouveau.tableau())
    }

    // ---- T-F3 (Phase 2) : vidange groupée -------------------------------

    @Test
    fun `T-F3 - un groupe s ecrit en un appel et se relit frame par frame`() {
        val flux = FluxCompteur()
        val payloads = listOf(byteArrayOf(1), byteArrayOf(2, 2), byteArrayOf(3, 3, 3))

        FrameCodec.writeFrames(flux, payloads)

        assertEquals(1, flux.appels)
        val entree = ByteArrayInputStream(flux.tableau())
        payloads.forEach { attendu ->
            assertArrayEquals(attendu, FrameCodec.readFrame(entree))
        }
    }

    @Test
    fun `T-F3 - le groupe est la concatenation exacte des frames individuelles`() {
        val individuelles = ByteArrayOutputStream()
        listOf(byteArrayOf(9, 8, 7), byteArrayOf(6), byteArrayOf(5, 4)).forEach {
            FrameCodec.writeFrame(individuelles, it)
        }

        val groupe = FluxCompteur()
        FrameCodec.writeFrames(groupe, listOf(byteArrayOf(9, 8, 7), byteArrayOf(6), byteArrayOf(5, 4)))

        assertArrayEquals(individuelles.toByteArray(), groupe.tableau())
    }

    @Test
    fun `T-F3 - un groupe vide ou un payload vide sont refuses`() {
        val flux = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.writeFrames(flux, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.writeFrames(flux, listOf(byteArrayOf(1), ByteArray(0)))
        }
    }

    // ---- T-F2 (Phase 2) : lecture d'en-tête en un appel -----------------

    /** Flux livrant ses octets par PETITS morceaux (réveils partiels). */
    private class FluxMorcele(
        private val octets: ByteArray,
        private val tailleMorceau: Int,
    ) : java.io.InputStream() {
        private var curseur = 0

        override fun read(): Int = if (curseur >= octets.size) -1 else octets[curseur++].toInt() and 0xFF

        override fun read(
            tampon: ByteArray,
            depart: Int,
            longueur: Int,
        ): Int {
            if (curseur >= octets.size) return -1
            val n = minOf(longueur, tailleMorceau, octets.size - curseur)
            System.arraycopy(octets, curseur, tampon, depart, n)
            curseur += n
            return n
        }
    }

    @Test
    fun `T-F2 - un en tete livre en morceaux se lit quand meme`() {
        val flux = ByteArrayOutputStream()
        FrameCodec.writeFrame(flux, byteArrayOf(7, 7, 7))
        // Deux octets par réveil : la boucle « jusqu'à 4 » réassemble.
        val entree = FluxMorcele(flux.toByteArray(), tailleMorceau = 2)

        assertArrayEquals(byteArrayOf(7, 7, 7), FrameCodec.readFrame(entree))
    }

    @Test
    fun `T-F2 - EOF au premier octet reste une fin de connexion propre`() {
        // Zéro octet disponible : read(b, 0, 4) rend -1 au PREMIER appel —
        // la sémantique d'EOF de l'ancienne lecture octet par octet tient.
        val entree = FluxMorcele(ByteArray(0), tailleMorceau = 4)

        assertThrows(EOFException::class.java) {
            FrameCodec.readFrame(entree)
        }
    }

    @Test
    fun `T-F2 - EOF en plein en tete reste une frame tronquee`() {
        // Deux octets puis fin : la valeur partielle décodée alimente
        // l'exception, comme avant (mêmes chiffres, mêmes types).
        val entree = FluxMorcele(byteArrayOf(0, 0), tailleMorceau = 4)

        assertThrows(FrameTronqueeException::class.java) {
            FrameCodec.readFrame(entree)
        }
    }
}
