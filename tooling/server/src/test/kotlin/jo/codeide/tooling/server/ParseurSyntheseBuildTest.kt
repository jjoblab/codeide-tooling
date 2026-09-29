package jo.codeide.tooling.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests du [ParseurSyntheseBuild] (v0.39.1, correctif n°4) : extraction
 * structurée de la dernière ligne stdout de Gradle — formats non
 * incrémental et incrémental (« N actionable tasks: M executed[,
 * K up-to-date] »), lignes parasites ignorées, robustesse aux espaces
 * autour de la virgule.
 */
class ParseurSyntheseBuildTest {
    @Test
    fun `un build complet non incrémental se lit avec ses deux comptes`() {
        val synthese = ParseurSyntheseBuild.analyser("37 actionable tasks: 37 executed")
        assertEquals(37, synthese?.actionableTasks)
        assertEquals(37, synthese?.executedTasks)
        // Sans « , K up-to-date », la partie incrémentale est absente.
        assertNull(synthese?.upToDateTasks)
    }

    @Test
    fun `un build incrémental se lit avec ses trois comptes`() {
        val synthese = ParseurSyntheseBuild.analyser("37 actionable tasks: 2 executed, 35 up-to-date")
        assertEquals(37, synthese?.actionableTasks)
        assertEquals(2, synthese?.executedTasks)
        assertEquals(35, synthese?.upToDateTasks)
    }

    @Test
    fun `un build avec espaces variables autour de la virgule est accepte`() {
        val synthese = ParseurSyntheseBuild.analyser("42 actionable tasks: 10 executed,32 up-to-date")
        assertEquals(42, synthese?.actionableTasks)
        assertEquals(10, synthese?.executedTasks)
        assertEquals(32, synthese?.upToDateTasks)
    }

    @Test
    fun `un build sans espace avant executable est rejete`() {
        // Format exigé par Gradle : « N actionable tasks: M executed » —
        // l'espace après les deux-points est OBLIGATOIRE (sinon, ce
        // n'est pas une synthèse Gradle mais une conversation).
        val synthese = ParseurSyntheseBuild.analyser("37 actionable tasks:37 executed")
        assertNull(synthese)
    }

    @Test
    fun `une ligne parasite n est pas une synthese`() {
        assertNull(ParseurSyntheseBuild.analyser("> Task :app:compileKotlin"))
        assertNull(ParseurSyntheseBuild.analyser("BUILD SUCCESSFUL"))
        assertNull(ParseurSyntheseBuild.analyser("Configuration on demand is an incubating feature"))
        assertNull(ParseurSyntheseBuild.analyser(""))
        assertNull(ParseurSyntheseBuild.analyser("  "))
    }

    @Test
    fun `une ligne avec espaces de bordure est trapee puis acceptee`() {
        val synthese = ParseurSyntheseBuild.analyser("   5 actionable tasks: 5 executed   ")
        assertEquals(5, synthese?.actionableTasks)
        assertEquals(5, synthese?.executedTasks)
        assertNull(synthese?.upToDateTasks)
    }

    @Test
    fun `une ligne avec un zero est acceptee`() {
        // Un build qui n'a exécuté aucune tâche actionnable est légitime
        // (par exemple : `./gradlew --help` ou une tâche sans travail).
        val synthese = ParseurSyntheseBuild.analyser("0 actionable tasks: 0 executed")
        assertEquals(0, synthese?.actionableTasks)
        assertEquals(0, synthese?.executedTasks)
    }

    @Test
    fun `une ligne avec zero executable et tout a jour est acceptee`() {
        val synthese = ParseurSyntheseBuild.analyser("15 actionable tasks: 0 executed, 15 up-to-date")
        assertEquals(15, synthese?.actionableTasks)
        assertEquals(0, synthese?.executedTasks)
        assertEquals(15, synthese?.upToDateTasks)
    }
}
