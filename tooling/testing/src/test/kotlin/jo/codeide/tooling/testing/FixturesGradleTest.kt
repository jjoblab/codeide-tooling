package jo.codeide.tooling.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Garde-fous des fixtures (§7.2) : les projets existent réellement
 * en ressources et se copient intégralement vers un répertoire temporaire
 * — un build de test ne doit jamais retomber sur une fixture construite
 * en place. v0.92.0 : la liste porte la fixture ANDROID (mission « LSP
 * Java » étape 1, ADR 0113) en cinquième position.
 */
class FixturesGradleTest {
    @Test
    fun `les cinq fixtures attendues existent`() {
        assertEquals(
            listOf("minimal-java", "erreur-compilation", "multi-module", "tache-longue", "android-simple"),
            FixturesGradle.noms,
        )
    }

    @Test
    fun `copier restitue la structure complete - settings et sources`() {
        val temporaire = FixturesGradle.dossierTemporaire()
        try {
            val copie = FixturesGradle.copier("minimal-java", temporaire)

            assertTrue(Files.isRegularFile(copie.resolve("settings.gradle.kts")))
            assertTrue(Files.isRegularFile(copie.resolve("build.gradle.kts")))
            assertTrue(Files.isRegularFile(copie.resolve("src/main/java/demo/Main.java")))
        } finally {
            temporaire.toFile().deleteRecursively()
        }
    }

    @Test
    fun `copier rejette une fixture inconnue`() {
        val temporaire = FixturesGradle.dossierTemporaire()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FixturesGradle.copier("inexistante", temporaire)
            }
        } finally {
            temporaire.toFile().deleteRecursively()
        }
    }
}
