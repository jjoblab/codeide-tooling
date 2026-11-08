package jo.codeide.tooling.server

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TID noyau du fil courant ([FilNoyau]) : la Phase 1 cherchait schedstat
 * par l'identifiant séquentiel JVM — sonde morte-née (le fichier
 * `/proc/self/task/<id-jvm>/schedstat` n'existe jamais). Ce test verrouille
 * le contrat du remplaçant : le tid désigne une entrée EXISTANTE de
 * `/proc/self/task`, ou la sonde se dégrade honnêtement en `-1`.
 */
class FilNoyauTest {
    @Test
    fun `le tid noyau designe une tache existante de proc self task`() {
        val tid = FilNoyau.tidCourant()
        if (tid <= 0) {
            // Non-Linux ou lien indisponible : -1, jamais un tid bidon.
            assertTrue("dégradation honnête attendue (-1), obtenu $tid", tid == -1L)
            return
        }
        assertTrue(
            "le tid $tid doit désigner /proc/self/task/$tid",
            File("/proc/self/task/$tid").isDirectory,
        )
    }
}
