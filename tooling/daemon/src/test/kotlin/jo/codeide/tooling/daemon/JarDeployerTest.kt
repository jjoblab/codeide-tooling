package jo.codeide.tooling.daemon

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
                assertTrue("l'ancien JAR devait rester en place et complet", jar.isFile)
            } finally {
                cible.setWritable(true)
            }
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

/** Source toujours absente (asset corrompu). */
internal class SourceJarAbsent : SourceJarTooling {
    override fun flux(): InputStream = throw IOException("asset tooling/gradle-server.jar introuvable")
}
