package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.TaskInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache serveur de la dernière sync réussie (v4, §3.1) : traduction DTO
 * (sérialisables Tooling API) vers les types du protocole prêts à
 * publier, remplacement à CHAQUE sync aboutie, isolation par chemin
 * canonique de projet.
 */
class CacheSyncTest {
    /** Échantillon couvrant chaque nature d'entrée (MODULE, DOSSIER, AAR,
     *  JAR avec sources attachées, nature inconnue — repli). */
    private fun deposerEchantillon(cache: CacheSync) {
        cache.deposer(
            projectDir = "/proj",
            taches =
                listOf(
                    TacheDto(path = ":app:assembleDebug", group = "build", displayName = "assembleDebug"),
                ),
            modules =
                listOf(
                    ModuleClasspathDto(
                        name = ":app",
                        sourceDirs = listOf("/proj/app/src/main", "/proj/app/src/test"),
                        entries =
                            listOf(
                                EntreeClasspathDto(
                                    path = "kotlin-stdlib.jar",
                                    kind = "JAR",
                                    scope = "compile",
                                    sources = "kotlin-stdlib-sources.jar",
                                ),
                                EntreeClasspathDto(
                                    path = ":core:ui",
                                    kind = "MODULE",
                                    scope = "compile",
                                    sources = null,
                                ),
                                EntreeClasspathDto(
                                    path = "sortie-classes",
                                    kind = "DOSSIER",
                                    scope = null,
                                    sources = null,
                                ),
                                EntreeClasspathDto(
                                    path = "material.aar",
                                    kind = "AAR",
                                    scope = "runtime",
                                    sources = null,
                                ),
                                EntreeClasspathDto(
                                    path = "nature-inconnue",
                                    kind = "INCONNU",
                                    scope = null,
                                    sources = null,
                                ),
                            ),
                    ),
                ),
        )
    }

    @Test
    fun `le depot traduit taches et modules en types du protocole`() {
        val cache = CacheSync()
        deposerEchantillon(cache)

        val entree = cache.consulter("/proj")!!
        assertEquals(
            listOf(TaskInfo(path = ":app:assembleDebug", group = "build", displayName = "assembleDebug")),
            entree.taches,
        )
        val module = entree.modules.single()
        assertEquals(":app", module.name)
        assertEquals(listOf("/proj/app/src/main", "/proj/app/src/test"), module.sourceDirs)
        assertEquals("compile", module.entries[0].scope)
        assertEquals("kotlin-stdlib-sources.jar", module.entries[0].sources)
    }

    @Test
    fun `chaque nature d entree trouve son enumeration, l inconnue replie sur JAR`() {
        val cache = CacheSync()
        deposerEchantillon(cache)

        val module = cache.consulter("/proj")!!.modules.single()
        assertEquals(
            listOf(
                ClasspathKind.JAR,
                ClasspathKind.MODULE,
                ClasspathKind.DOSSIER,
                ClasspathKind.AAR,
                ClasspathKind.JAR,
            ),
            module.entries.map { it.kind },
        )
        assertEquals(null, module.entries[1].sources)
    }

    @Test
    fun `le cache est vide avant tout depot`() {
        assertNull(CacheSync().consulter("/proj"))
    }

    @Test
    fun `un second depot remplace l entree et les projets restent isoles`() {
        val cache = CacheSync()
        cache.deposer("/a", taches = listOf(TacheDto("t1", null, "t1")), modules = emptyList())
        cache.deposer(
            "/a",
            taches = listOf(TacheDto("t2", null, "t2"), TacheDto("t3", null, "t3")),
            modules = emptyList(),
        )
        cache.deposer("/b", taches = emptyList(), modules = emptyList())

        // Fraîcheur : seule la DERNIÈRE sync de /a compte (§3.1).
        assertEquals(listOf("t2", "t3"), cache.consulter("/a")!!.taches.map { it.path })
        assertTrue(cache.consulter("/b")!!.taches.isEmpty())
    }
}
