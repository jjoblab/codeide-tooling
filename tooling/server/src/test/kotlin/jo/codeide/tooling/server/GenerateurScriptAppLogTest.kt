package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ApplogCoordonnees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du générateur du script d'init de l'injection applog (mission
 * « Exécuter » R2, ADR 0103) : le TEXTE généré est le contrat — chaque
 * garantie de l'ADR s'y lit (dépôt local au niveau des réglages,
 * dépendance sur les classpaths d'exécution debug, interrupteur honnête).
 *
 * Le comportement RÉEL du script (beforeSettings, injection, résolution)
 * est éprouvé par [ScriptAppLogIntegrationTest] contre un VRAI Gradle.
 */
class GenerateurScriptAppLogTest {
    @get:Rule
    public val temporaires: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private val depot = File("/data/data/jo.codeide/files/applog-repo")

    @Test
    fun `le script porte le chemin du depot et la coordonnee`() {
        val texte = GenerateurScriptAppLog.texte(depot, ApplogCoordonnees.COORDONNEE)

        assertTrue(texte.contains("uri(\"file:///data/data/jo.codeide/files/applog-repo\")"))
        // La coordonnée voyage en notation CARTE (groupe/artefact/version
        // séparés — les surcharges Kotlin de DependencyHandler tranchent
        // proprement dessus, ADR 0103 §7).
        assertTrue(texte.contains("\"group\" to \"${ApplogCoordonnees.GROUPE}\""))
        assertTrue(texte.contains("\"name\" to \"${ApplogCoordonnees.ARTEFACT}\""))
        assertTrue(texte.contains("\"version\" to \"${ApplogCoordonnees.VERSION}\""))
    }

    @Test
    fun `le depot passe par les reglages jamais par un projet`() {
        val texte = GenerateurScriptAppLog.texte(depot, ApplogCoordonnees.COORDONNEE)

        // Niveau RÉGLAGES (compatible FAIL_ON_PROJECT_REPOS, ADR 0103 §7)…
        assertTrue(texte.contains("gradle.beforeSettings"))
        assertTrue(texte.contains("dependencyResolutionManagement"))
        // …et AUCUN dépôt ajouté dans un projet.
        assertFalse(texte.contains("project.repositories"))
    }

    @Test
    fun `la dependance passe par les configurations RuntimeClasspath`() {
        val texte = GenerateurScriptAppLog.texte(depot, ApplogCoordonnees.COORDONNEE)

        assertTrue(texte.contains("configurations.configureEach"))
        // Le gestionnaire du PROJET est capturé AVANT le bloc configureEach :
        // à l'intérieur, « dependencies » désignerait la collection de la
        // configuration, pas le DependencyHandler (piège mesuré en test
        // d'intégration réel).
        assertTrue(texte.contains("val gestionnaireDependances = dependencies"))
        assertTrue(texte.contains("gestionnaireDependances.add(name, notationAppLog)"))
    }

    @Test
    fun `l interrupteur utilisateur est honnete et lisible`() {
        val texte = GenerateurScriptAppLog.texte(depot, ApplogCoordonnees.COORDONNEE)

        // findProperty lit -P ET gradle.properties : les deux mondes de
        // propriétés, le dernier mot à l'utilisateur (ADR 0103 §7).
        assertTrue(texte.contains("findProperty(\"codeide.applog.isEnabled\") != \"false\""))
    }

    @Test
    fun `ecrire pose le fichier exactement la ou on l attend`() {
        val cible = File(temporaires.newFolder("run"), ApplogCoordonnees.NOM_SCRIPT_INIT)

        GenerateurScriptAppLog.ecrire(cible, depot, ApplogCoordonnees.COORDONNEE)

        assertTrue(cible.isFile)
        assertEquals(
            GenerateurScriptAppLog.texte(depot, ApplogCoordonnees.COORDONNEE),
            cible.readText(),
        )
    }

    @Test
    fun `le script ne contient aucun marqueur residuel`() {
        val texte = GenerateurScriptAppLog.texte(depot, ApplogCoordonnees.COORDONNEE)

        assertFalse("marqueur dépôt résiduel", texte.contains("@@DEPOT@@"))
        assertFalse("marqueur coordonnée résiduel", texte.contains("@@COORDONNEE@@"))
    }
}
