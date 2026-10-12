package jo.codeide.tooling.daemon

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Tests du [JarDeployer] (§5.4 « marqueur de version ») : première copie,
 * recopie seulement au changement de source, échec propre d'une source
 * absente.
 */
class JarDeployerTest {
    @Test
    fun `le premier deploiement copie le jar et ecrit le marqueur`() =
        runBlocking {
            val cible = dossierTemporaire("deploy-premier")
            val octets = ByteArray(4_096) { indice -> (indice % 199).toByte() }
            val deployeur = JarDeployer(SourceJarMemoire(octets), cible, DispatchersIoDirect())

            val jar = deployeur.deployer()

            assertEquals(File(cible, "gradle-server.jar"), jar)
            assertTrue("le JAR devait exister", jar.isFile)
            assertEquals(octets.size.toLong(), jar.length())
            val marqueur = File(cible, "version.txt")
            assertTrue("le marqueur devait exister", marqueur.isFile)
            assertEquals(64, marqueur.readText().length)
        }

    @Test
    fun `un redemarrage sans changement ne recopie PAS le jar`() =
        runBlocking {
            val cible = dossierTemporaire("deploy-stable")
            val octets = ByteArray(2_048) { indice -> (indice % 71).toByte() }
            val deployeur = JarDeployer(SourceJarMemoire(octets), cible, DispatchersIoDirect())

            deployeur.deployer()
            val jar = File(cible, "gradle-server.jar")
            jar.setLastModified(HORODATAGE_FIXE)
            Thread.sleep(50)

            deployeur.deployer()
            assertEquals(
                "un redéploiement inchangé ne devait pas réécrire le JAR",
                HORODATAGE_FIXE,
                jar.lastModified(),
            )
        }

    @Test
    fun `un changement de source declenche la recopie`() =
        runBlocking {
            val cible = dossierTemporaire("deploy-change")
            val source = SourceJarVariable()
            val deployeur = JarDeployer(source, cible, DispatchersIoDirect())

            source.octets = ByteArray(1_024) { 1 }
            deployeur.deployer()
            val jar = File(cible, "gradle-server.jar")
            assertEquals(1_024L, jar.length())

            Thread.sleep(50)
            source.octets = ByteArray(2_048) { 2 }
            deployeur.deployer()
            assertEquals(2_048L, jar.length())
            assertEquals(2, jar.inputStream().use { it.read() })
        }

    @Test
    fun `une source absente echoue proprement`() {
        val cible = dossierTemporaire("deploy-absent")
        val deployeur = JarDeployer(SourceJarAbsent(), cible, DispatchersIoDirect())

        val echec =
            runBlocking {
                try {
                    deployeur.deployer()
                    null
                } catch (indisponible: IOException) {
                    indisponible
                }
            }
        assertTrue("le déploiement devait échouer par IOException", echec != null)
        assertFalse(File(cible, "gradle-server.jar").isFile)
        // v0.99.1 : l'échec est TYÉ et ACTIONNABLE — le message porte le
        // chemin de l'asset ET la cause originale (pas un « The source
        // file doesn't exist. » nu, inexploitable).
        assertTrue(
            "l'échec devait être typé SourceJarIndisible : $echec",
            echec is EchecDeploiementJar.SourceJarIndisible,
        )
        assertTrue(echec?.message?.contains("tooling/gradle-server.jar") == true)
        assertTrue(echec?.message?.contains("réinstaller") == true)
        assertTrue(echec?.cause is java.io.FileNotFoundException)
    }

    @Test
    fun `un repertoire cible increable echoue avec le chemin en clair`() {
        // v0.99.1 (journal de terrain « <chemin>: The source file doesn't
        // exist. ») : l'ancien code ignorait le résultat de mkdirs() — un
        // fichier sur le chemin du répertoire produisait une
        // FileNotFoundException ENOENT sans contexte sur le `.tmp`.
        // Désormais : échec TYÉ, chemin complet, retentable.
        val parent = dossierTemporaire("deploy-parent")
        parent.mkdirs()
        val bloquant = File(parent, "outil-fichier")
        bloquant.writeText("un FICHIER bloque la création du répertoire")
        val cible = File(bloquant, "sous-dossier")
        val deployeur = JarDeployer(SourceJarMemoire(ByteArray(64) { 1 }), cible, DispatchersIoDirect())

        val echec =
            runBlocking {
                try {
                    deployeur.deployer()
                    null
                } catch (indisponible: IOException) {
                    indisponible
                }
            }
        assertTrue(
            "l'échec devait être typé CibleInaccessible : $echec",
            echec is EchecDeploiementJar.CibleInaccessible,
        )
        assertTrue(echec?.message?.contains(cible.absolutePath) == true)
        assertFalse(File(cible, "gradle-server.jar").isFile)
    }

    @Test
    fun `un remplacement impossible echoue proprement`() =
        runBlocking {
            val cible = dossierTemporaire("deploy-remplacement")
            val source = SourceJarVariable()
            val deployeur = JarDeployer(source, cible, DispatchersIoDirect())

            // Premier déploiement sain, puis l'ancien JAR devient
            // inamovible (répertoire en lecture seule) : remplacer doit
            // échouer SANS corruption (le .tmp reste, le jar d'origine
            // demeure l'ancienne version complète).
            source.octets = ByteArray(512) { 1 }
            deployeur.deployer()
            val jar = File(cible, "gradle-server.jar")
            cible.setWritable(false)

            try {
                source.octets = ByteArray(512) { 2 }
                val echec =
                    runBlocking {
                        try {
                            deployeur.deployer()
                            null
                        } catch (impossible: IOException) {
                            impossible
                        }
                    }
                assertTrue("le remplacement devait échouer par IOException", echec != null)
                // v0.99.1 : échec typé RETENTABLE (l'état du stockage peut
                // changer — la boucle de vie du daemon consommera une
                // tentative au lieu de déclarer l'orchestrateur mort).
                assertTrue(
                    "l'échec devait être typé EcritureImpossible : $echec",
                    echec is EchecDeploiementJar.EcritureImpossible,
                )
                assertTrue("l'ancien JAR devait rester en place et complet", jar.isFile)
            } finally {
                cible.setWritable(true)
            }
        }

    @Test
    fun `deux deploiements concurrents reussissent tous deux`() =
        runBlocking {
            // v0.100.1 (pile de terrain JarDeployer.kt:183, « The source
            // file doesn't exist. ») : le `.tmp` à nom FIXE faisait mourir
            // le perdant de la course — un NoSuchFileException BRUT, classé
            // définitif alors que le JAR ÉTAIT déployé. Le `.tmp` est
            // désormais UNIQUE et l'ouverture EXCLUSIVE : les deux appels
            // concurrents réussissent, le second voit le marqueur frais.
            val cible = dossierTemporaire("deploy-concurrent")
            val octets = ByteArray(64_000) { indice -> (indice % 251).toByte() }
            val deployeur = JarDeployer(SourceJarMemoire(octets), cible, DispatchersIoDirect())

            coroutineScope {
                repeat(8) {
                    val a = async { deployeur.deployer() }
                    val b = async { deployeur.deployer() }
                    a.await()
                    b.await()
                }
            }

            val jar = File(cible, "gradle-server.jar")
            assertTrue("le JAR devait exister", jar.isFile)
            assertEquals(octets.size.toLong(), jar.length())
            assertEquals(
                "aucun `.tmp` ne devait survivre",
                emptyList<String>(),
                cible
                    .listFiles()
                    .orEmpty()
                    .filter { it.name.endsWith(".tmp") }
                    .map { it.name },
            )
            assertEquals(64, File(cible, "version.txt").readText().length)
        }

    @Test
    fun `le tmp a nom fixe herite des versions precedentes est purge`() =
        runBlocking {
            // v0.100.0 et avant écrivaient `gradle-server.jar.tmp` à nom
            // fixe : un déploiement interrompu y laissait jusqu'à 7,6 Mo.
            // Le nouveau code n'écrit plus JAMAIS ce nom — la purge à
            // l'entrée est sûre (aucun process vivant ne le possède).
            val cible = dossierTemporaire("deploy-purge-herite")
            val octets = ByteArray(1_024) { 1 }
            val heritage = File(cible, "gradle-server.jar.tmp")
            cible.mkdirs()
            heritage.writeText("deploiement interrompu d'une version anterieure")

            val jar = JarDeployer(SourceJarMemoire(octets), cible, DispatchersIoDirect()).deployer()

            assertTrue("le JAR devait exister", jar.isFile)
            assertFalse("le `.tmp` hérité devait être purgé", heritage.isFile)
            assertEquals(octets.size.toLong(), jar.length())
        }

    private companion object {
        /** Horodatage arbitraire fixe (2001-02-03) pour l'assertion mtime. */
        const val HORODATAGE_FIXE = 981_173_817_000L
    }
}

/** Source à contenu variable (chaque `flux()` sert les octets courants). */
private class SourceJarVariable : SourceJarTooling {
    var octets: ByteArray = ByteArray(0)

    override fun flux(): InputStream = octets.inputStream()
}

/** Source toujours absente (asset corrompu) — le message EXACT que
 *  l'AssetManager d'Android produit pour un asset manquant (AOSP
 *  AssetManager.open : FileNotFoundException("Asset file: <nom>")) —
 *  v0.99.1 : le déploiement doit l'envelopper en échec typé. */
internal class SourceJarAbsent : SourceJarTooling {
    override fun flux(): InputStream = throw java.io.FileNotFoundException("Asset file: tooling/gradle-server.jar")
}
