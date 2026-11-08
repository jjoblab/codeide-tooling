package jo.codeide.tooling.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Tests de l'[EmpreinteProjet] (E1, Phase 2) : stabilité à l'identique,
 * sensibilité aux fichiers qui DÉFINISSENT le build (y compris les
 * sous-projets et buildSrc — plus conservatrice que le client),insensibilité
 * aux sorties (`build/`) et aux caches, chaîne vide sur racine illisible.
 */
class EmpreinteProjetTest {
    private fun projet(nom: String): java.io.File = Files.createTempDirectory("empreinte-$nom").toFile()

    private fun ecrire(
        dossier: java.io.File,
        chemin: String,
        contenu: String,
    ) {
        val cible = java.io.File(dossier, chemin)
        cible.parentFile?.mkdirs()
        cible.writeText(contenu)
    }

    @Test
    fun `deux projets identiques ont la meme empreinte - elle est hexadecimale`() {
        val empreintes =
            (1..2).map {
                val dossier = projet("identique")
                ecrire(dossier, "settings.gradle.kts", "rootProject.name = \"demo\"\n")
                ecrire(dossier, "build.gradle.kts", "plugins { }\n")
                ecrire(dossier, "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=x\n")
                EmpreinteProjet.calculer(dossier)
            }
        assertEquals(empreintes[0], empreintes[1])
        assertTrue(empreintes[0].matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `modifier un script de build change l empreinte`() {
        val dossier = projet("mutation")
        ecrire(dossier, "build.gradle.kts", "plugins { }\n")
        val avant = EmpreinteProjet.calculer(dossier)

        ecrire(dossier, "build.gradle.kts", "plugins { id(\"java\") }\n")

        assertNotEquals(avant, EmpreinteProjet.calculer(dossier))
    }

    @Test
    fun `le build gradle d un sous-projet compte - plus conservateur que le client`() {
        val dossier = projet("sous-projet")
        ecrire(dossier, "settings.gradle.kts", "include(\":app\")\n")
        ecrire(dossier, "app/build.gradle.kts", "plugins { }\n")
        val avant = EmpreinteProjet.calculer(dossier)

        ecrire(dossier, "app/build.gradle.kts", "plugins { id(\"java\") }\n")

        assertNotEquals(avant, EmpreinteProjet.calculer(dossier))
    }

    @Test
    fun `buildSrc compte dans l empreinte`() {
        val dossier = projet("buildsrc")
        ecrire(dossier, "buildSrc/build.gradle.kts", "repositories { }\n")
        val avant = EmpreinteProjet.calculer(dossier)

        ecrire(dossier, "buildSrc/build.gradle.kts", "repositories { mavenCentral() }\n")

        assertNotEquals(avant, EmpreinteProjet.calculer(dossier))
    }

    @Test
    fun `les sorties build et les caches ne comptent pas`() {
        val dossier = projet("sorties")
        ecrire(dossier, "build.gradle.kts", "plugins { }\n")
        val avant = EmpreinteProjet.calculer(dossier)

        // Des sorties et caches qui BOUGENT (rebuild, daemon, IDE) :
        ecrire(dossier, "build/classes/app.txt", "binaire")
        ecrire(dossier, ".gradle/vcs-1/gc.properties", "gc")
        ecrire(dossier, ".idea/workspace.xml", "<idea/>")

        assertEquals(avant, EmpreinteProjet.calculer(dossier))
    }

    @Test
    fun `un fichier non gradle ne compte pas - un fichier gradle ajoute si`() {
        val dossier = projet("fichiers")
        ecrire(dossier, "build.gradle.kts", "plugins { }\n")
        val avant = EmpreinteProjet.calculer(dossier)

        ecrire(dossier, "lisezmoi.md", "du texte qui change")
        assertEquals(avant, EmpreinteProjet.calculer(dossier))

        ecrire(dossier, "gradle/libs.versions.toml", "kotlin = \"2.2\"\n")
        assertNotEquals(avant, EmpreinteProjet.calculer(dossier))
    }

    @Test
    fun `racine illisible - empreinte vide (sync forcee, jamais de restitution)`() {
        assertEquals("", EmpreinteProjet.calculer(java.io.File("/inexistant/aucun")))
    }
}
