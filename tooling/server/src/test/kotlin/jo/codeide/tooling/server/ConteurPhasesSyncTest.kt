package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.DetailTelechargement
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.SyncPhase
import jo.codeide.tooling.protocol.SyncProgress
import jo.codeide.tooling.protocol.ToolingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Machine à états des phases de sync v4 (§3.1) : départs annoncés une
 * seule fois (idempotence — la durée resterait mensongère), conclusions
 * avec durée MESURÉE, progressions auto-ouvrantes (DEPENDANCES n'existe
 * que si des téléchargements ont LIEU), cumul des octets des dépendances
 * terminées, compteur global des configurations, clôture des phases
 * restées ouvertes. Le bus factice capture les publications sans socket.
 */
class ConteurPhasesSyncTest {
    /** Bus de capture : garde l'ordre d'arrivée, publier n'oppose aucune
     *  contre-pression (la file bornée du vrai bus n'est pas sous test). */
    private class BusCapture : EventBus {
        val evenements = CopyOnWriteArrayList<ToolingEvent>()

        override fun publier(evenement: ToolingEvent) {
            evenements += evenement
        }

        override fun demarrer() = Unit

        override fun arreter() = Unit
    }

    private val bus = BusCapture()
    private val conteur = ConteurPhasesSync(projectDir = "/proj", bus = bus)

    private val progressions: List<SyncProgress>
        get() = bus.evenements.filterIsInstance<SyncProgress>()

    @Test
    fun `le depart d une phase est annonce une seule fois, non termine`() {
        conteur.ouvrir(SyncPhase.DAEMON)
        conteur.ouvrir(SyncPhase.DAEMON) // idempotent : un seul départ

        assertEquals(1, progressions.size)
        val depart = progressions.single()
        assertEquals(SyncPhase.DAEMON, depart.phase)
        assertFalse(depart.terminee)
        assertEquals(0L, depart.dureeMs)
        assertEquals("/proj", depart.projectDir)
        assertEquals(GradleProtocol.PROTOCOL_VERSION, depart.protocolVersion)
    }

    @Test
    fun `la conclusion porte la duree mesurée et reste idempotente`() {
        conteur.ouvrir(SyncPhase.MODELE_TACHES)
        Thread.sleep(60)
        conteur.conclure(SyncPhase.MODELE_TACHES)
        conteur.conclure(SyncPhase.MODELE_TACHES) // déjà conclue : no-op

        val terminees = progressions.filter { it.terminee }
        assertEquals(1, terminees.size)
        // Durée mesurée entre départ et conclusion — jamais devinée (règle 9) :
        // sleep(60) garantit au moins ~60 ms réellement écoulées.
        assertTrue("durée mesurée", terminees.single().dureeMs >= 30)
    }

    @Test
    fun `une progression auto-ouvre la phase puis porte le detail`() {
        conteur.progression(
            SyncPhase.DISTRIBUTION,
            element = "gradle-9.7.1-bin.zip",
            octetsRecus = 42,
            octetsTotal = 130,
        )

        assertEquals(2, progressions.size)
        assertFalse(progressions[0].terminee)
        val detail = progressions[1]
        assertEquals(SyncPhase.DISTRIBUTION, detail.phase)
        assertEquals(42L, detail.octetsRecus)
        assertEquals(130L, detail.octetsTotal)
        assertEquals("gradle-9.7.1-bin.zip", detail.element)
    }

    @Test
    fun `les octets des dependances terminees se cumulent et l element courant suit`() {
        conteur.surTelechargement(
            DetailTelechargement(element = "kotlin-stdlib.jar", octetsRecus = 100, termine = false),
        )
        conteur.surTelechargement(
            DetailTelechargement(element = "kotlin-stdlib.jar", octetsRecus = 100, termine = true, compteur = 1),
        )
        conteur.surTelechargement(
            DetailTelechargement(element = "material.jar", octetsRecus = 50, termine = true, compteur = 2),
        )

        val dependances = progressions.filter { it.phase == SyncPhase.DEPENDANCES }
        val derniere = dependances.last()
        assertEquals(150L, derniere.octetsRecus)
        assertEquals("material.jar", derniere.element)
        assertEquals(2, derniere.compteur)
        assertEquals("material.jar", conteur.elementDependanceCourant())
    }

    @Test
    fun `la configuration alimente la phase, global des terminees a la fin`() {
        conteur.surConfiguration(element = ":app", terminee = false, compteur = 1)
        conteur.surConfiguration(element = ":app", terminee = true, compteur = 1)
        conteur.surConfiguration(element = ":feature:editor", terminee = false, compteur = 2)

        // La 1re donnée auto-ouvre la phase (départ) puis publie le détail :
        // les événements détaillés (portant un élément) sont au nombre de 3.
        val detaillees =
            progressions.filter { it.phase == SyncPhase.CONFIGURATION && it.element != null }
        assertEquals(3, detaillees.size)
        // Départ d'un projet : compteur BRUT reçu (n-ième projet vu).
        assertEquals(1, detaillees[0].compteur)
        assertEquals(":app", detaillees[0].element)
        // Fin d'un projet : compteur GLOBAL des projets terminés.
        assertEquals(1, detaillees[1].compteur)
        // Départ du 2e projet : compteur brut (2), pas le global.
        assertEquals(2, detaillees[2].compteur)
        assertEquals(":feature:editor", detaillees[2].element)
    }

    @Test
    fun `conclureTout clot chaque phase restee ouverte, une fois chacune`() {
        conteur.ouvrir(SyncPhase.OUTILS)
        conteur.ouvrir(SyncPhase.DISTRIBUTION)
        conteur.conclure(SyncPhase.OUTILS)
        conteur.conclureTout()

        assertTrue(progressions.any { it.phase == SyncPhase.OUTILS && it.terminee })
        assertTrue(progressions.any { it.phase == SyncPhase.DISTRIBUTION && it.terminee })
        // OUTILS était déjà conclue : conclureTout ne la re-conclut pas.
        assertEquals(1, progressions.count { it.phase == SyncPhase.OUTILS && it.terminee })
    }

    @Test
    fun `chaque evenement porte un identifiant distinct`() {
        conteur.ouvrir(SyncPhase.OUTILS)
        conteur.ouvrir(SyncPhase.DAEMON)
        conteur.conclureTout()

        val ids = progressions.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    // ---- Phase SAUTÉE (v5 — distribution déjà en cache) ------------------

    @Test
    fun `une phase sautee se publie conclue sans duree, une seule fois`() {
        conteur.sauter(SyncPhase.DISTRIBUTION, element = "gradle-9.7.1-bin.zip")
        conteur.sauter(SyncPhase.DISTRIBUTION) // idempotente : publiée une fois

        assertEquals(1, progressions.size)
        val sautee = progressions.single()
        assertEquals(SyncPhase.DISTRIBUTION, sautee.phase)
        assertTrue("conclue sans travail", sautee.terminee)
        assertTrue("marquée sautée — le client rend « en cache »", sautee.sautee)
        assertEquals("aucune durée : aucun travail n'a eu lieu", 0L, sautee.dureeMs)
        assertEquals("gradle-9.7.1-bin.zip", sautee.element)
    }

    @Test
    fun `une phase ouverte ne peut plus etre sautee - le travail a eu lieu`() {
        conteur.ouvrir(SyncPhase.DISTRIBUTION, element = "gradle-9.7.1-bin.zip")
        conteur.sauter(SyncPhase.DISTRIBUTION) // ignorée : la phase est ouverte

        assertEquals(1, progressions.size)
        assertFalse("le départ annoncé reste la vérité", progressions.single().sautee)

        // La conclusion normale garde la main (durée mesurée, pas « en cache »).
        conteur.conclure(SyncPhase.DISTRIBUTION)
        val terminee = progressions.filter { it.terminee }.single()
        assertFalse(terminee.sautee)
    }

    @Test
    fun `une phase sautee ne se re-conclut pas par conclureTout`() {
        conteur.sauter(SyncPhase.DISTRIBUTION)
        conteur.ouvrir(SyncPhase.DAEMON)
        conteur.conclureTout()

        // DISTRIBUTION : publiée UNE fois (sautée), jamais re-conclue.
        assertEquals(1, progressions.count { it.phase == SyncPhase.DISTRIBUTION })
        assertTrue(progressions.filter { it.phase == SyncPhase.DISTRIBUTION }.single().sautee)
    }
}
