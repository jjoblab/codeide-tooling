package jo.codeide.tooling.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Sondes locales de l'état de la distribution Gradle (v4 — phase
 * DISTRIBUTION réelle) : lecture de l'URL du wrapper, marqueur
 * d'installation `.ok`, octets partiels `.part`. Les sondes directory
 * balayent le `GRADLE_USER_HOME` EFFECTIF du process en LECTURE SEULE :
 * un nom de distribution inexistant rend le résultat déterministe sur
 * toute machine (aucune écriture dans le vrai home Gradle).
 */
class EtatsDistributionTest {
    @get:Rule
    val dossier = TemporaryFolder()

    @Test
    fun `sans fichier de wrapper l url est nulle`() {
        assertNull(EtatsDistribution.lireUrlWrapper(dossier.root))
    }

    @Test
    fun `l url de distribution du wrapper est lue`() {
        val wrapper = dossier.newFolder("gradle", "wrapper")
        File(wrapper, "gradle-wrapper.properties").writeText(
            "distributionBase=GRADLE_USER_HOME\n" +
                "distributionPath=wrapper/dists\n" +
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n",
        )

        assertEquals(
            "https://services.gradle.org/distributions/gradle-9.7.1-bin.zip",
            EtatsDistribution.lireUrlWrapper(dossier.root),
        )
    }

    @Test
    fun `un wrapper sans cle distributionUrl rend une url nulle`() {
        val wrapper = dossier.newFolder("gradle", "wrapper")
        File(wrapper, "gradle-wrapper.properties").writeText("distributionBase=GRADLE_USER_HOME\n")

        assertNull(EtatsDistribution.lireUrlWrapper(dossier.root))
    }

    @Test
    fun `sans wrapper la distribution embarquee est deja la`() {
        // null = la Tooling API résout sa propre distribution : rien à
        // télécharger, la phase DISTRIBUTION ne s'ouvre jamais.
        assertTrue(EtatsDistribution.estInstallee(null))
    }

    @Test
    fun `une distribution absente du GRADLE_USER_HOME n est pas installee`() {
        // Balayage réel (lecture seule) du home Gradle du process : le nom
        // de dossier « codeide-inexistante-1.0-bin » n'existe nulle part.
        assertFalse(
            EtatsDistribution.estInstallee(
                "https://example.com/distributions/codeide-inexistante-1.0-bin.zip",
            ),
        )
    }

    @Test
    fun `les octets partiels se sondent sans effet de bord`() {
        // Lecture seule du `wrapper/dists` effectif : quel que soit son
        // état (absent, vide, téléchargement en cours), le total est
        // cohérent — jamais négatif, jamais d'exception.
        assertTrue(EtatsDistribution.octetsPartiels() >= 0L)
    }
}
