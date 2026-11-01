package jo.codeide.tooling.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Métronome du daemon Gradle (Phase 1, §3.1c) : reconnaissance des repères
 * `[[CIDE-T seq epochMs]]` et génération du script d'init (modèle à
 * substituant unique, comme le générateur applog).
 */
class ReperesMetronomeTest {
    @Test
    fun `un repere bien forme est reconnu et decode`() {
        val repere = ReperesMetronome.analyser("[[CIDE-T 42 1730000000250]]")
        assertNotNull(repere)
        assertEquals(42L, repere!!.sequence)
        assertEquals(1_730_000_000_250L, repere.epochMs)
        assertTrue(ReperesMetronome.estReper("[[CIDE-T 42 1730000000250]]"))
    }

    @Test
    fun `une ligne de build reelle n est jamais un repere`() {
        assertNull(ReperesMetronome.analyser("> Task :app:assembleDebug"))
        assertNull(ReperesMetronome.analyser("BUILD FAILED in 36s"))
        assertNull(ReperesMetronome.analyser("[[CIDE-T abc 123]]"))
        assertNull(ReperesMetronome.analyser("[[CIDE-T 1]]"))
        assertNull(ReperesMetronome.analyser(""))
        assertFalse(ReperesMetronome.estReper("banc 000001 xxxx"))
    }

    @Test
    fun `un repere avec espaces de bord est reconnu - trim defensif`() {
        assertNotNull(ReperesMetronome.analyser("  [[CIDE-T 3 1730000000000]]  "))
    }

    @Test
    fun `le script genere porte le chemin d arret et la periode`() {
        val texte = GenerateurScriptMetronome.texte(java.io.File("/tmp/arret-codeide.test"))
        assertTrue(texte.contains("/tmp/arret-codeide.test"))
        assertTrue(texte.contains("250L"))
        assertTrue(texte.contains("[[CIDE-T"))
        assertFalse("le marqueur doit être substitué", texte.contains("@@ARRET@@"))
    }

    @Test
    fun `ecrire pose le script a cote de la cible demandee`() {
        val dossier = Files.createTempDirectory("metronome-test")
        val cible = dossier.resolve("sous").resolve("metronome.init.gradle.kts").toFile()
        val arret = dossier.resolve("metronome.arret").toFile()
        GenerateurScriptMetronome.ecrire(cible, arret)
        assertTrue(cible.exists())
        assertTrue(cible.readText().contains(arret.absolutePath))
    }

    @Test
    fun `le metronome de build se relance et s arrete par fichier`() {
        val dossier = Files.createTempDirectory("metronome-build-test")
        val arret = dossier.resolve("arret").toFile()
        val metronome = MetronomeBuild(scriptInit = "/tmp/script.init", fichierArret = arret)
        metronome.arreter()
        assertTrue(arret.exists())
        metronome.relancer()
        assertFalse(arret.exists())
    }
}
