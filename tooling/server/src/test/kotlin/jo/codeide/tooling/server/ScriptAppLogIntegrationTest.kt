package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ApplogCoordonnees
import org.gradle.tooling.GradleConnector
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests d'intégration RÉELS du script d'init de l'injection applog
 * (mission « Exécuter » R2, ADR 0103) : un VRAI Gradle construit un projet
 * témoin avec le script généré — c'est le SEUL endroit qui prouve que
 * `beforeSettings`, le dépôt local et l'ajout de dépendance passent sur du
 * Gradle réel, avec les modes de réglages hostiles.
 *
 * Le projet témoin n'a PAS de wrapper : la Tooling API résout sa
 * distribution comme pour toutes les fixtures du module (§7.2) ; le
 * premier test qui connecte amorce le daemon (long une fois), les
 * suivants le réutilisent.
 *
 * Le projet témoin reproduit le pire cas : `FAIL_ON_PROJECT_REPOS`
 * (ajouter un dépôt de PROJET échouerait) et des configurations aux noms
 * exacts d'AGP — le script doit injecter la dépendance sur les
 * `*RuntimeClasspath` DEBUG de l'app, PAS ceux des tests, PAS du release.
 */
class ScriptAppLogIntegrationTest {
    @get:Rule
    public val temporaires: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun `le script injecte la dependance dans les classpaths debug et nulle part ailleurs`() {
        val projet = projetTemoin()
        val depot = depotMavenFactice()
        val script = File(temporaires.newFolder(), ApplogCoordonnees.NOM_SCRIPT_INIT)
        GenerateurScriptAppLog.ecrire(script, depot, ApplogCoordonnees.COORDONNEE)

        val sortie = construire(projet, listOf("-I", script.absolutePath))

        // Les DEUX classpaths debug de l'application voient l'artefact…
        assertTrue(sortie.contains("debug=1"))
        assertTrue(sortie.contains("paidDebug=1"))
        // …PAS ceux du release, PAS ceux des tests.
        assertTrue(sortie.contains("release=0"))
        assertTrue(sortie.contains("debugAndroidTest=0"))
        assertTrue(sortie.contains("debugUnitTest=0"))
    }

    @Test
    fun `l interrupteur de l utilisateur coupe toute injection`() {
        val projet = projetTemoin()
        val depot = depotMavenFactice()
        val script = File(temporaires.newFolder(), ApplogCoordonnees.NOM_SCRIPT_INIT)
        GenerateurScriptAppLog.ecrire(script, depot, ApplogCoordonnees.COORDONNEE)

        val sortie =
            construire(
                projet,
                listOf("-I", script.absolutePath, "-Pcodeide.applog.isEnabled=false"),
            )

        assertTrue("injection coupée partout : $sortie", sortie.contains("debug=0"))
        assertTrue(sortie.contains("release=0"))
    }

    // ------------------------------------------------------------------
    // Fabrication du projet témoin et du dépôt factice.
    // ------------------------------------------------------------------

    /**
     * Projet témoin : `FAIL_ON_PROJECT_REPOS` strict (un dépôt de projet
     * ferait ÉCHOUER le build), AUCUN dépôt déclaré (le seul dépôt est
     * celui du script — aucune résolution ne sort sur le réseau), et CINQ
     * configurations aux noms exacts d'AGP.
     */
    private fun projetTemoin(): File {
        val dossier = temporaires.newFolder("projet")
        dossier
            .resolve("settings.gradle.kts")
            .writeText(
                """
                rootProject.name = "temoin-applog"
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories { }
                }
                """.trimIndent(),
            )
        dossier
            .resolve("build.gradle.kts")
            .writeText(
                """
                // Noms exacts des configurations d'exécution d'AGP : le
                // script ne les crée PAS — il les reconnaît.
                val examinees =
                    listOf(
                        "debug" to "debugRuntimeClasspath",
                        "paidDebug" to "paidDebugRuntimeClasspath",
                        "release" to "releaseRuntimeClasspath",
                        "debugAndroidTest" to "debugAndroidTestRuntimeClasspath",
                        "debugUnitTest" to "debugUnitTestRuntimeClasspath",
                    )
                examinees.forEach { (_, nom) -> configurations.create(nom) }

                tasks.register("compter") {
                    doLast {
                        examinees.forEach { (libelle, nom) ->
                            val resolves =
                                configurations.named(nom).get().resolve().filter { it.isFile }
                            println("COMPTE-${'$'}{libelle}=${'$'}{resolves.size}")
                        }
                    }
                }
                """.trimIndent(),
            )
        return dossier
    }

    /**
     * Dépôt maven factice en disposition standard — l'artefact est un jar
     * minimal valide (Gradle le vérifie à la résolution).
     */
    private fun depotMavenFactice(): File {
        val racine = temporaires.newFolder("depot")
        val dossier =
            racine
                .resolve(ApplogCoordonnees.GROUPE.replace('.', '/'))
                .resolve(ApplogCoordonnees.ARTEFACT)
                .resolve(ApplogCoordonnees.VERSION)
        dossier.mkdirs()
        // Packaging « jar » dans le POM : le fichier porte l'extension .jar
        // (en production, l'AAR réel porte .aar avec packaging aar — la
        // résolution AGP la transforme ; ici, jar simple = pas d'AGP).
        val jar = dossier.resolve("applog-runtime-${ApplogCoordonnees.VERSION}.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
        }
        dossier
            .resolve(ApplogCoordonnees.NOM_POM)
            .writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>${ApplogCoordonnees.GROUPE}</groupId>
                  <artifactId>${ApplogCoordonnees.ARTEFACT}</artifactId>
                  <version>${ApplogCoordonnees.VERSION}</version>
                  <packaging>jar</packaging>
                </project>
                """.trimIndent(),
            )
        return racine
    }

    /** Lance le build `compter` via la Tooling API et retourne le stdout. */
    private fun construire(
        projet: File,
        arguments: List<String>,
    ): String {
        val sortie = ByteArrayOutputStream()
        GradleConnector
            .newConnector()
            .forProjectDirectory(projet)
            .connect()
            .use { connexion ->
                connexion
                    .newBuild()
                    .forTasks("compter")
                    .withArguments(arguments + "--console=plain")
                    .setStandardOutput(sortie)
                    .run()
            }
        return sortie.toString(Charsets.UTF_8.name())
    }
}
