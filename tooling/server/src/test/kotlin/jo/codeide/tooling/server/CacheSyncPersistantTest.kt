package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ResolvedDependencyKind
import jo.codeide.tooling.protocol.ResolvedDependencyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Tests du [CacheSyncPersistant] (E1/E2, Phase 2) : restitution mémoire,
 * restitution DISQUE après un « redémarrage » (nouvelle instance, même
 * dossier — LE scénario de la réouverture de projet), invalidation par
 * empreinte ET par contexte, purge mémoire qui GARDE le disque, cache de
 * dépendances (E2) et bornage LRU.
 */
class CacheSyncPersistantTest {
    private fun dossierTemporaire(nom: String): java.io.File = Files.createTempDirectory("cache-sync-$nom").toFile()

    private fun tachesEchantillon(): List<TacheDto> =
        listOf(TacheDto(path = ":app:assembleDebug", group = "build", displayName = "assembleDebug"))

    private fun modulesEchantillon(): List<ModuleClasspathDto> =
        listOf(
            ModuleClasspathDto(
                name = ":app",
                sourceDirs = listOf("/proj/app/src/main"),
                entries =
                    listOf(
                        EntreeClasspathDto(path = "kotlin-stdlib.jar", kind = "JAR", scope = "compile", sources = null),
                    ),
            ),
        )

    private fun dependancesEchantillon(): Map<String, List<ResolvedDependencyNode>> =
        mapOf(
            ":app" to
                listOf(
                    ResolvedDependencyNode(
                        group = "org.jetbrains.kotlin",
                        name = "kotlin-stdlib",
                        versionDemandee = "2.2.10",
                        versionRetenue = "2.2.10",
                        configuration = "",
                        type = ResolvedDependencyKind.LIBRARY,
                        raison = "",
                        transitives = emptyList(),
                    ),
                ),
        )

    /** Résultat de sync complet, tel que le dépose le [SyncHandler]. */
    private fun resultatEchantillon(dependances: Boolean = true): ResultatModelesSync =
        ResultatModelesSync(
            taches = tachesEchantillon(),
            modules = modulesEchantillon(),
            dependances = if (dependances) dependancesEchantillon() else emptyMap(),
        )

    @Test
    fun `un depot suivi de la meme cle se restitue en memoire`() {
        val cache = CacheSyncPersistant(dossierTemporaire("memoire"))
        cache.deposer("/proj", "empreinte-a", "contexte-a", resultatEchantillon())

        val restituee = cache.restituer("/proj", "empreinte-a", "contexte-a")

        assertEquals(1, restituee?.taches?.size)
        assertEquals(1, restituee?.modules?.size)
    }

    @Test
    fun `une empreinte differente ne se restitue pas`() {
        val cache = CacheSyncPersistant(dossierTemporaire("empreinte"))
        cache.deposer("/proj", "empreinte-a", "contexte-a", resultatEchantillon())

        assertNull(cache.restituer("/proj", "empreinte-b", "contexte-a"))
    }

    @Test
    fun `un contexte different (arguments changes) invalide la restitution`() {
        val cache = CacheSyncPersistant(dossierTemporaire("contexte"))
        cache.deposer("/proj", "empreinte-a", "contexte-a", resultatEchantillon())

        assertNull(cache.restituer("/proj", "empreinte-a", "contexte-b"))
    }

    @Test
    fun `apres un redemarrage (nouvelle instance) le disque restitue le projet inchange`() {
        val dossier = dossierTemporaire("redemarrage")
        CacheSyncPersistant(dossier).deposer("/proj", "empreinte-a", "contexte-a", resultatEchantillon())

        // « Redémarrage » : nouvelle instance, même dossier de persistance.
        val seconde = CacheSyncPersistant(dossier)
        val restituee = seconde.restituer("/proj", "empreinte-a", "contexte-a")

        assertEquals(1, restituee?.taches?.size)
        // E2 : les dépendances ont survécu AUSSI.
        assertEquals(1, seconde.dependancesDe("/proj", ":app")?.size)
    }

    @Test
    fun `apres un redemarrage un projet modifie ne se restitue pas`() {
        val dossier = dossierTemporaire("modifie")
        CacheSyncPersistant(dossier).deposer("/proj", "empreinte-a", "contexte-a", resultatEchantillon())

        assertNull(CacheSyncPersistant(dossier).restituer("/proj", "empreinte-changee", "contexte-a"))
    }

    @Test
    fun `la purge memoire garde le disque - la reouverture reste le benefice de E1`() {
        val dossier = dossierTemporaire("purge")
        val premiere = CacheSyncPersistant(dossier)
        premiere.deposer("/proj", "empreinte-a", "contexte-a", resultatEchantillon())
        premiere.purger("/proj")

        assertNull(premiere.consulter("/proj"))
        // Le disque répond au « redémarrage » suivant : fermer un projet ne
        // détruit pas le gain de sa réouverture.
        assertEquals(1, CacheSyncPersistant(dossier).restituer("/proj", "empreinte-a", "contexte-a")?.taches?.size)
    }

    @Test
    fun `une empreinte vide ne se persiste pas - la sync complete reste la seule voie`() {
        val dossier = dossierTemporaire("vide")
        CacheSyncPersistant(dossier).deposer("/proj", "", "contexte-a", resultatEchantillon())

        assertNull(CacheSyncPersistant(dossier).restituer("/proj", "", "contexte-a"))
        assertEquals(0, dossier.listFiles()?.size ?: 0)
    }

    @Test
    fun `dossier null - comportement du CacheSync seul, aucune persistance`() {
        val cache = CacheSyncPersistant(null)
        cache.deposer("/proj", "empreinte-a", "contexte-a", resultatEchantillon())

        assertEquals(1, cache.restituer("/proj", "empreinte-a", "contexte-a")?.taches?.size)
        assertNull(CacheSyncPersistant(null).restituer("/proj", "empreinte-a", "contexte-a"))
    }

    @Test
    fun `E2 - un arbre resolu hors sync complete le cache et survit au redemarrage`() {
        val dossier = dossierTemporaire("e2")
        val cache = CacheSyncPersistant(dossier)
        cache.deposer("/proj", "empreinte-a", "contexte-a", resultatEchantillon(dependances = false))
        assertNull(cache.dependancesDe("/proj", ":app"))

        cache.deposerDependances("/proj", ":app", dependancesEchantillon().getValue(":app"))

        assertEquals(1, cache.dependancesDe("/proj", ":app")?.size)
        val neuve = CacheSyncPersistant(dossier)
        neuve.restituer("/proj", "empreinte-a", "contexte-a")
        assertEquals(1, neuve.dependancesDe("/proj", ":app")?.size)
    }

    @Test
    fun `LRU - au dela de huit projets le plus ancien fichier disparait`() {
        val dossier = dossierTemporaire("lru")
        val cache = CacheSyncPersistant(dossier)
        for (index in 1..9) {
            cache.deposer("/proj-$index", "empreinte-$index", "contexte", resultatEchantillon())
            Thread.sleep(2)
        }

        val fichiers = dossier.listFiles { f -> f.name.endsWith(".json") }!!
        assertEquals(8, fichiers.size)
        // Le premier projet (le plus ancien) a été évincé DU DISQUE : une
        // instance neuve ne peut plus le restituer — le neuvième, oui.
        val neuve = CacheSyncPersistant(dossier)
        assertNull(neuve.restituer("/proj-1", "empreinte-1", "contexte"))
        assertTrue(neuve.restituer("/proj-9", "empreinte-9", "contexte") != null)
    }
}
