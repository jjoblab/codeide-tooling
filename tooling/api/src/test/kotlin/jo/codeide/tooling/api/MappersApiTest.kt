package jo.codeide.tooling.api

import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.Diagnostic
import jo.codeide.tooling.protocol.DiagnosticSeverity
import jo.codeide.tooling.protocol.HeapEvent
import jo.codeide.tooling.protocol.StreamKind
import jo.codeide.tooling.protocol.TaskInfo
import jo.codeide.tooling.protocol.TasksResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mappers protocol → api (G2) : la frontière câble/modèle reste fidèle. */
class MappersApiTest {
    @Test
    fun `une ligne de sortie traverse sans altération`() {
        val message =
            BuildOutput(
                id = "1",
                protocolVersion = 2,
                buildId = "b1",
                stream = StreamKind.STDOUT,
                line = "tâche compilée ✓",
                timestampMs = 123L,
            )
        val ligne = message.versLigneSortie()
        assertEquals("b1", ligne.buildId)
        assertEquals(StreamKind.STDOUT, ligne.flux)
        assertEquals("tâche compilée ✓", ligne.ligne)
        assertEquals(123L, ligne.horodatageMs)
    }

    @Test
    fun `un build réussi devient REUSSI avec sa durée`() {
        val fin =
            BuildFinished(
                id = "2",
                protocolVersion = 2,
                buildId = "b1",
                succeeded = true,
                durationMs = 4_500,
            )
        val etat = fin.versEtatBuild()
        assertEquals(StatutBuild.REUSSI, etat.statut)
        assertEquals(4_500L, etat.dureeMs)
        assertNull(etat.messageEchec)
    }

    @Test
    fun `un build échoué devient ECHOUE avec son message`() {
        val fin =
            BuildFinished(
                id = "3",
                protocolVersion = 2,
                buildId = "b2",
                succeeded = false,
                durationMs = 300,
                failureMessage = "compilation Java échouée",
            )
        val etat = fin.versEtatBuild()
        assertEquals(StatutBuild.ECHOUE, etat.statut)
        assertEquals("compilation Java échouée", etat.messageEchec)
    }

    @Test
    fun `un instantané de tas conserve ses mégaoctets`() {
        val evenement =
            HeapEvent(
                id = "4",
                protocolVersion = 2,
                usedMb = 48,
                maxMb = 512,
            )
        val tas = evenement.versInstantaneTas()
        assertEquals(48L, tas.moUtilises)
        assertEquals(512L, tas.moMax)
    }

    @Test
    fun `une tâche garde son chemin, son groupe et son nom`() {
        val tache =
            TaskInfo(
                path = ":app:saluer",
                group = "build",
                displayName = "saluer",
            )
        val info = tache.versInfoTache()
        assertEquals(":app:saluer", info.chemin)
        assertEquals("build", info.groupe)
        assertEquals("saluer", info.nomAffiche)
    }

    @Test
    fun `une tâche sans groupe reste sans groupe`() {
        val tache = TaskInfo(path = ":saluer", group = null, displayName = "saluer")
        assertNull(tache.versInfoTache().groupe)
    }

    @Test
    fun `la liste des tâches traverse intégralement`() {
        val resultat =
            TasksResult(
                id = "5",
                protocolVersion = 2,
                projectDir = "/p",
                tasks =
                    listOf(
                        TaskInfo(":a", null, "a"),
                        TaskInfo(":b", "build", "b"),
                    ),
            )
        val infos = resultat.versInfosTaches()
        assertEquals(2, infos.size)
        assertEquals(":b", infos[1].chemin)
    }

    @Test
    fun `un diagnostic garde sa gravité et sa position`() {
        val message =
            Diagnostic(
                id = "6",
                protocolVersion = 2,
                severity = DiagnosticSeverity.ERROR,
                file = "/p/src/Casse.java",
                line = 7,
                column = 9,
                message = "';' attendu",
                source = "javac",
            )
        val diagnostic = message.versDiagnostic()
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severite)
        assertEquals("/p/src/Casse.java", diagnostic.fichier)
        assertEquals(7L, diagnostic.ligne)
        assertEquals(9L, diagnostic.colonne)
        assertEquals("';' attendu", diagnostic.message)
        assertEquals("javac", diagnostic.source)
    }
}
