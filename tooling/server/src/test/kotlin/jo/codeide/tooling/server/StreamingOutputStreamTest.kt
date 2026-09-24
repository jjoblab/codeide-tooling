package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.StreamKind
import jo.codeide.tooling.protocol.ToolingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lignes de sortie de build (§4.3) : découpage par '\n', dernière ligne sans
 * terminaison publiée à la fermeture, '\r' de fin Windows retiré, suites
 * UTF-8 multi-octets réassemblées entre écritures.
 *
 * Les lignes VIDEES ne sont pas publiées : elles ne portent aucune
 * information de build (décision ADR 0040 — l'UI n'a pas besoin de spam).
 */
class StreamingOutputStreamTest {
    /** Bus collecteur synchrone — publier() ne bloque jamais ici. */
    private class BusCollecteur : EventBus {
        val lignes = CopyOnWriteArrayList<BuildOutput>()

        override fun publier(evenement: ToolingEvent) {
            if (evenement is BuildOutput) lignes += evenement
        }

        override fun demarrer() = Unit

        override fun arreter() = Unit
    }

    private fun bus() = BusCollecteur()

    private fun textes(bus: BusCollecteur) = bus.lignes.map { it.line }

    @Test
    fun `chaque ligne termine par un événement`() {
        val bus = bus()
        StreamingOutputStream("b1", StreamKind.STDOUT, bus).use { sortie ->
            sortie.write("Bonjour\nau revoir\n".toByteArray())
        }
        assertEquals(listOf("Bonjour", "au revoir"), textes(bus))
    }

    @Test
    fun `la dernière ligne sans terminaison sort à la fermeture`() {
        val bus = bus()
        StreamingOutputStream("b1", StreamKind.STDOUT, bus).use { sortie ->
            sortie.write("première\n".toByteArray())
            sortie.write("fin sans saut".toByteArray())
        }
        assertEquals(listOf("première", "fin sans saut"), textes(bus))
    }

    @Test
    fun `le retour chariot de fin Windows est retiré`() {
        val bus = bus()
        StreamingOutputStream("b1", StreamKind.STDOUT, bus).use { sortie ->
            sortie.write("ligne\r\n".toByteArray())
        }
        assertEquals(listOf("ligne"), textes(bus))
    }

    @Test
    fun `les lignes vides ne sont pas publiées`() {
        val bus = bus()
        StreamingOutputStream("b1", StreamKind.STDOUT, bus).use { sortie ->
            sortie.write("a\n\nb\n".toByteArray())
        }
        assertEquals(listOf("a", "b"), textes(bus))
    }

    @Test
    fun `une suite UTF-8 coupée entre deux écritures est réassemblée`() {
        val bus = bus()
        val octets = "hétérogène".toByteArray(Charsets.UTF_8)
        StreamingOutputStream("b1", StreamKind.STDOUT, bus).use { sortie ->
            // é = 2 octets, è = 2 octets : couper en plein milieu de chacun.
            sortie.write(octets.copyOfRange(0, 4))
            sortie.write(octets.copyOfRange(4, octets.size))
            sortie.write('\n'.code)
        }
        assertEquals(listOf("hétérogène"), textes(bus))
    }

    @Test
    fun `les événements portent le build, le flux et un horodatage`() {
        val bus = bus()
        StreamingOutputStream("b7", StreamKind.STDERR, bus).use { sortie ->
            sortie.write("attention\n".toByteArray())
        }
        val evenement = bus.lignes.single()
        assertEquals("b7", evenement.buildId)
        assertEquals(StreamKind.STDERR, evenement.stream)
        assertTrue(evenement.timestampMs > 0)
        assertTrue(evenement.id.isNotEmpty())
    }

    @Test
    fun `l'écriture d'un seul octet à la fois fonctionne`() {
        val bus = bus()
        StreamingOutputStream("b1", StreamKind.STDOUT, bus).use { sortie ->
            "lettre à lettre\n".toByteArray().forEach { octet -> sortie.write(octet.toInt()) }
        }
        assertEquals(listOf("lettre à lettre"), textes(bus))
    }

    @Test
    fun `fermer deux fois ne publie rien de plus`() {
        val bus = bus()
        val sortie = StreamingOutputStream("b1", StreamKind.STDOUT, bus)
        sortie.write("x\n".toByteArray())
        sortie.close()
        sortie.close()
        assertEquals(listOf("x"), textes(bus))
    }

    @Test
    fun `un flux très long ne casse pas le découpage`() {
        val bus = bus()
        val attendues = (1..500).map { "ligne $it" }
        StreamingOutputStream("b1", StreamKind.STDOUT, bus).use { sortie ->
            attendues.forEach { sortie.write("$it\n".toByteArray()) }
        }
        assertEquals(attendues, textes(bus))
    }

    @Test
    fun `close sans aucune écriture ne publie rien`() {
        val bus = bus()
        StreamingOutputStream("b1", StreamKind.STDOUT, bus).close()
        assertTrue(bus.lignes.isEmpty())
    }
}
