package jo.codeide.tooling.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Arguments de l'orchestrateur (§4.1) : obligatoires, défauts, rejets. */
class ServerConfigTest {
    @Test
    fun `les arguments complets sont lus tels quels`() {
        val config =
            ServerConfig.analyser(
                arrayOf(
                    "--socket",
                    "/data/run/gradle.sock",
                    "--secret",
                    "s3cr3t",
                    "--log-level",
                    "WARN",
                    "--heap-intervalle-ms",
                    "1000",
                ),
            )
        assertEquals("/data/run/gradle.sock", config.cheminSocket)
        assertEquals("s3cr3t", config.secret)
        assertEquals(ServerConfig.NiveauJournal.WARN, config.niveauJournal)
        assertEquals(1_000L, config.intervalleTasMs)
    }

    @Test
    fun `le niveau de journal et l'intervalle de tas ont des défauts`() {
        val config =
            ServerConfig.analyser(
                arrayOf("--socket", "/tmp/g.sock", "--secret", "x"),
            )
        assertEquals(ServerConfig.NiveauJournal.INFO, config.niveauJournal)
        assertEquals(ServerConfig.INTERVALLE_TAS_MS_DEFAUT, config.intervalleTasMs)
    }

    @Test
    fun `le secret et le socket sont obligatoires`() {
        assertNotNull(
            assertThrows(IllegalArgumentException::class.java) { ServerConfig.analyser(arrayOf("--secret", "x")) },
        )
        assertNotNull(
            assertThrows(IllegalArgumentException::class.java) { ServerConfig.analyser(arrayOf("--socket", "/s")) },
        )
        assertNotNull(assertThrows(IllegalArgumentException::class.java) { ServerConfig.analyser(emptyArray()) })
    }

    @Test
    fun `un niveau de journal inconnu est rejeté avec un message d'usage`() {
        val erreur =
            assertThrows(IllegalArgumentException::class.java) {
                ServerConfig.analyser(arrayOf("--socket", "/s", "--secret", "x", "--log-level", "BOF"))
            }
        assertEquals(true, erreur.message?.contains("BOF") == true)
    }

    @Test
    fun `un intervalle de tas illisible est rejeté`() {
        assertNotNull(
            assertThrows(IllegalArgumentException::class.java) {
                ServerConfig.analyser(arrayOf("--socket", "/s", "--secret", "x", "--heap-intervalle-ms", "vite"))
            },
        )
    }

    @Test
    fun `un argument inconnu est rejeté`() {
        assertNotNull(
            assertThrows(IllegalArgumentException::class.java) {
                ServerConfig.analyser(arrayOf("--socket", "/s", "--secret", "x", "--port", "1"))
            },
        )
    }

    @Test
    fun `une valeur manquante est rejetée`() {
        assertNotNull(
            assertThrows(IllegalArgumentException::class.java) {
                ServerConfig.analyser(arrayOf("--socket", "/s", "--secret"))
            },
        )
    }
}
