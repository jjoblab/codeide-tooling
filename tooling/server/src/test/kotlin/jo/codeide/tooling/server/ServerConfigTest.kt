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

    @Test
    fun `l instrumentation de transport est un drapeau sans valeur - Phase 1`() {
        val config =
            ServerConfig.analyser(
                arrayOf("--socket", "/s", "--secret", "x", "--instru-transport"),
            )
        assertEquals(true, config.instrumentationTransport)
        assertEquals(null, config.banc)
    }

    @Test
    fun `le banc socket seul lit ses parametres avec ses defauts - Phase 1`() {
        val config =
            ServerConfig.analyser(
                arrayOf(
                    "--socket",
                    "/s",
                    "--secret",
                    "x",
                    "--banc-socket-seul",
                    "--banc-lignes",
                    "10000",
                    "--banc-cadence-ms",
                    "50",
                    "--banc-longueur-ligne",
                    "200",
                    "--banc-contenu",
                    "multi",
                ),
            )
        assertNotNull(config.banc)
        assertEquals(10_000, config.banc!!.lignes)
        assertEquals(50L, config.banc!!.cadenceMs)
        assertEquals(200, config.banc!!.longueurLigne)
        assertEquals(ServerConfig.ContenuLigneBanc.MULTI, config.banc!!.contenu)

        val defauts =
            ServerConfig.analyser(arrayOf("--socket", "/s", "--secret", "x", "--banc-socket-seul"))
        assertNotNull(defauts.banc)
        assertEquals(ServerConfig.BANC_LIGNES_DEFAUT, defauts.banc!!.lignes)
        assertEquals(ServerConfig.BANC_CADENCE_MS_DEFAUT, defauts.banc!!.cadenceMs)
        assertEquals(ServerConfig.BANC_LONGUEUR_LIGNE_DEFAUT, defauts.banc!!.longueurLigne)
        assertEquals(ServerConfig.ContenuLigneBanc.PLAIN, defauts.banc!!.contenu)
    }

    @Test
    fun `un contenu de banc inconnu est rejete avec un message clair`() {
        val erreur =
            assertThrows(IllegalArgumentException::class.java) {
                ServerConfig.analyser(
                    arrayOf("--socket", "/s", "--secret", "x", "--banc-socket-seul", "--banc-contenu", "bof"),
                )
            }
        assertEquals(true, erreur.message?.contains("bof") == true)
    }

    @Test
    fun `les parametres du banc ne font rien sans le drapeau du banc`() {
        val config =
            ServerConfig.analyser(
                arrayOf("--socket", "/s", "--secret", "x", "--banc-lignes", "42"),
            )
        assertEquals(null, config.banc)
    }
}
