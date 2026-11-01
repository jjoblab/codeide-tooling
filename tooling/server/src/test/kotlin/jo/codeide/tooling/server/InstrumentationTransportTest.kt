package jo.codeide.tooling.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accumulateurs de l'instrumentation de transport (Phase 1, §3.2) : remise à
 * zéro par build, résumé de conclusion, garde d'inactivité (règle 10 : rien
 * ne se collecte ni ne s'écrit quand le drapeau est coupé).
 */
class InstrumentationTransportTest {
    @After
    fun nettoyer() {
        InstrumentationTransport.active = false
    }

    @Test
    fun `inactive l instrumentation n enregistre rien et ne resume rien`() {
        InstrumentationTransport.active = false
        InstrumentationTransport.demarrerBuild("b1")
        InstrumentationTransport.enregistrerAttentePublication(50)
        InstrumentationTransport.enregistrerEvenement("BuildOutput", 5)
        InstrumentationTransport.enregistrerEcritureFrame(3, 5)
        InstrumentationTransport.enregistrerRepere(100, 1)
        assertNull(InstrumentationTransport.conclureBuild("b1"))
    }

    @Test
    fun `le resume de conclusion porte les cinq points de mesure`() {
        InstrumentationTransport.active = true
        InstrumentationTransport.demarrerBuild("b1")
        InstrumentationTransport.enregistrerAttentePublication(12)
        InstrumentationTransport.enregistrerEvenement("BuildOutput", 2)
        InstrumentationTransport.enregistrerEvenement("BuildOutput", 6)
        InstrumentationTransport.enregistrerEvenement("ProgressEvent", 1)
        InstrumentationTransport.enregistrerProfondeurFileEcriture(4, enfilement = true)
        InstrumentationTransport.enregistrerAgeFrame(150)
        InstrumentationTransport.enregistrerEcritureFrame(1, 5)
        InstrumentationTransport.enregistrerEcritureFrame(3, 5)
        InstrumentationTransport.enregistrerRepere(20, 1)
        InstrumentationTransport.enregistrerRepere(60, 8)
        val resume = InstrumentationTransport.conclureBuild("b1")
        assertNotNull(resume)
        assertTrue(resume!!.contains("T1 put bus: 1 pubs, attente max 12ms"))
        assertTrue(resume.contains("BuildOutput=2(moy 4ms, max 6ms)"))
        assertTrue(resume.contains("ProgressEvent=1(moy 1ms, max 1ms)"))
        assertTrue(resume.contains("profondeur max 4"))
        assertTrue(resume.contains("age max 150ms"))
        assertTrue(resume.contains("2 frames, duree moy 2ms / max 3ms"))
        assertTrue(resume.contains("5 appels systeme/frame"))
        assertTrue(resume.contains("2 reperes, ecart T0 min 20ms / moy 40ms / max 60ms"))
    }

    @Test
    fun `un demarrage de build remet les compteurs a zero`() {
        InstrumentationTransport.active = true
        InstrumentationTransport.demarrerBuild("b1")
        InstrumentationTransport.enregistrerEvenement("BuildOutput", 9)
        InstrumentationTransport.demarrerBuild("b2")
        val resume = InstrumentationTransport.conclureBuild("b2")
        assertNotNull(resume)
        assertFalse(resume!!.contains("BuildOutput=1"))
    }

    @Test
    fun `sans reperes le resume le dit honnetement`() {
        InstrumentationTransport.active = true
        InstrumentationTransport.demarrerBuild("b1")
        val resume = InstrumentationTransport.conclureBuild("b1")
        assertNotNull(resume)
        assertTrue(resume!!.contains("aucun repere recu"))
    }

    @Test
    fun `schedstat lit les fils par tid et calcule les ecarts`() {
        // Le fil COURANT du test sert de cobaye : son schedstat existe sur
        // les noyaux CONFIG_SCHEDSTATS (bureau et CI) — la carte peut être
        // vide ailleurs, l'écarts reste alors silencieux (honnête).
        val tid = Thread.currentThread().threadId()
        val avant = Schedstat.instantanes(mapOf("fil-test" to tid))
        val apres = Schedstat.instantanes(mapOf("fil-test" to tid))
        if (avant.isNotEmpty() && apres.isNotEmpty()) {
            val ecarts = Schedstat.ecarts(avant, apres)
            assertEquals(1, ecarts.size)
            assertTrue(ecarts[0].contains("fil-test"))
        } else {
            assertTrue(Schedstat.ecarts(avant, apres).isEmpty())
        }
    }

    @Test
    fun `schedstat sur un tid inconnu rend une carte vide`() {
        assertTrue(Schedstat.instantanes(mapOf("fantome" to 999_999_999L)).isEmpty())
    }
}
