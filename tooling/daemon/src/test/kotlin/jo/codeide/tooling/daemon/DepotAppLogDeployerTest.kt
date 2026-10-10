package jo.codeide.tooling.daemon

import jo.codeide.core.testing.TestDispatcherProvider
import jo.codeide.tooling.protocol.ApplogCoordonnees
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Tests du déployeur du dépôt maven applog (mission « Exécuter » R2,
 * ADR 0103) — disposition maven exacte, marqueur de version, atomicité :
 * le serveur Gradle de l'utilisateur résout
 * `jo.codeide:applog-runtime:1.0.0` ICI, la disposition doit être
 * STRICTEMENT celle que Gradle attend.
 */
class DepotAppLogDeployerTest {
    @get:Rule
    public val temporaires: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private var contenuAar = ByteArray(64) { (it * 7).toByte() }
    private var contenuPom = "<pom/>".toByteArray()

    private lateinit var cible: File

    @org.junit.Before
    public fun preparer() {
        // Après la création du dossier temporaire par la règle (jamais
        // dans un initialiseur de champ : la règle n'a pas encore joué).
        cible = temporaires.newFolder("applog-repo")
    }

    /** Source factice par fichiers (couture — même contrat que les assets). */
    private val source =
        object : SourceAppLog {
            override fun flux(nom: String): InputStream =
                when (nom) {
                    ApplogCoordonnees.NOM_AAR -> ByteArrayInputStream(contenuAar)
                    ApplogCoordonnees.NOM_POM -> ByteArrayInputStream(contenuPom)
                    else -> throw FileNotFoundException(nom)
                }
        }

    private fun deployeur(): DepotAppLogDeployer = DepotAppLogDeployer(source, cible)

    @Test
    fun `le depot respecte la disposition maven exacte`() =
        runBlocking {
            deployeur().deployer()

            val dossierVersion = cible.resolve("jo/codeide/applog-runtime/1.0.0")
            assertTrue(dossierVersion.resolve(ApplogCoordonnees.NOM_AAR).isFile)
            assertTrue(dossierVersion.resolve(ApplogCoordonnees.NOM_POM).isFile)
            // Aucun résidu temporaire : le renommage a tout emporté.
            dossierVersion.listFiles()!!.forEach { fichier ->
                assertFalse(fichier.name + " est un résidu temporaire", fichier.name.endsWith(".tmp"))
            }
        }

    @Test
    fun `le marqueur de version evite la recopie quand rien ne change`() =
        runBlocking {
            val avant = contenuAar.copyOf()
            deployeur().deployer()
            val marqueur = cible.resolve(".depot-applog-version")
            assertTrue(marqueur.isFile)
            val aar = cible.resolve("jo/codeide/applog-runtime/1.0.0/${ApplogCoordonnees.NOM_AAR}")
            val empreinteTemps = aar.lastModified()

            contenuAar = avant // mêmes octets : pas de recopie
            Thread.sleep(5) // le temps de fichier doit rester l'ancien
            deployeur().deployer()

            assertEquals(empreinteTemps, aar.lastModified())
        }

    @Test
    fun `un changement de bibliotheque redeploye le depot`() =
        runBlocking {
            deployeur().deployer()
            val aar = cible.resolve("jo/codeide/applog-runtime/1.0.0/${ApplogCoordonnees.NOM_AAR}")

            contenuAar = ByteArray(64) { (it * 3).toByte() } // nouvelle version
            Thread.sleep(5)
            deployeur().deployer()

            val lu = FileInputStream(aar).use { it.readBytes() }
            assertEquals(3, lu[1].toInt() and 0xFF)
        }

    @Test
    fun `la racine du depot est exactement le dossier cible`() =
        runBlocking {
            val racine = deployeur().deployer()

            assertEquals(cible.canonicalFile, racine.canonicalFile)
        }
}
