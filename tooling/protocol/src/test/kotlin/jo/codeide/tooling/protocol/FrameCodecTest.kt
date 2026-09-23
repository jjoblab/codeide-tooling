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
}
