package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.TaskFinished
import jo.codeide.tooling.protocol.TaskStarted
import jo.codeide.tooling.protocol.ToolingEvent
import org.gradle.tooling.Failure
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.OperationResult
import org.gradle.tooling.events.PluginIdentifier
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.SkippedResult
import org.gradle.tooling.events.StartEvent
import org.gradle.tooling.events.SuccessResult
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.task.TaskProgressEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pont évènements de tâches Gradle → protocole (§4.3) : démarrages et fins
 * de tâches, réussite/saut/échec — les autres évènements sont ignorés sans
 * erreur (le pont n'est enregistré que pour `OperationType.TASK`, mais il
 * doit rester silencieux sur tout ce qui arrive quand même).
 */
class ProgressBridgeTest {
    private class BusCollecteur : EventBus {
        val evenements = CopyOnWriteArrayList<ToolingEvent>()

        override fun publier(evenement: ToolingEvent) {
            evenements += evenement
        }

        override fun demarrer() = Unit

        override fun arreter() = Unit
    }

    /** Descripteur de tâche minimal (le chemin suffit au pont). */
    private class DescripteurTache(
        private val chemin: String?,
    ) : TaskOperationDescriptor {
        override fun getName(): String = chemin ?: ""

        override fun getDisplayName(): String = chemin ?: ""

        override fun getParent(): OperationDescriptor? = null

        override fun getTaskPath(): String? = chemin

        override fun getDependencies(): Set<OperationDescriptor> = emptySet()

        override fun getOriginPlugin(): PluginIdentifier? = null
    }

    /** Échec Gradle minimal pour [FailureResult]. */
    private class Echec(
        private val texte: String,
    ) : Failure {
        override fun getMessage(): String = texte

        override fun getDescription(): String = texte

        override fun getCauses(): List<Failure> = emptyList()

        override fun getProblems(): List<org.gradle.tooling.events.problems.Problem> = emptyList()
    }

    /** Démarrage de tâche (un seul rôle — comme les vrais évènements Gradle). */
    private class DemarrageTache(
        private val descripteur: TaskOperationDescriptor,
    ) : TaskProgressEvent,
        StartEvent {
        override fun getEventTime(): Long = 0L

        override fun getDisplayName(): String = "tâche ${descripteur.taskPath}"

        override fun getDescriptor(): TaskOperationDescriptor = descripteur
    }

    /** Fin de tâche avec son résultat. */
    private class FinTache(
        private val descripteur: TaskOperationDescriptor,
        private val resultat: OperationResult,
    ) : TaskProgressEvent,
        FinishEvent {
        override fun getEventTime(): Long = 0L

        override fun getDisplayName(): String = "tâche ${descripteur.taskPath}"

        override fun getDescriptor(): TaskOperationDescriptor = descripteur

        override fun getResult(): OperationResult = resultat
    }

    /** Résultat de réussite (aucune méthode propre). */
    private object Reussite : SuccessResult {
        override fun getStartTime(): Long = 0L

        override fun getEndTime(): Long = 1L
    }

    /** Résultat sauté (aucune méthode propre). */
    private object Saute : SkippedResult {
        override fun getStartTime(): Long = 0L

        override fun getEndTime(): Long = 1L
    }

    /** Résultat d'échec avec ses causes. */
    private class EchecResultat(
        private val causes: List<Failure>,
    ) : FailureResult {
        override fun getStartTime(): Long = 0L

        override fun getEndTime(): Long = 1L

        override fun getFailures(): List<Failure> = causes
    }

    /** Évènement hors tâche : ignoré sans erreur. */
    private class EvenementEtranger : ProgressEvent {
        override fun getEventTime(): Long = 0L

        override fun getDisplayName(): String = "opération étrangère"

        override fun getDescriptor(): OperationDescriptor? = null
    }

    private fun pont(bus: BusCollecteur): ProgressListener = ProgressBridge("b1", bus)

    @Test
    fun `le démarrage d'une tâche publie TaskStarted avec son chemin`() {
        val bus = BusCollecteur()
        pont(bus).statusChanged(DemarrageTache(DescripteurTache(":app:saluer")))
        val evenement = bus.evenements.single() as TaskStarted
        assertEquals("b1", evenement.buildId)
        assertEquals(":app:saluer", evenement.taskPath)
        assertTrue(evenement.id.isNotEmpty())
    }

    @Test
    fun `la fin réussie d'une tâche publie TaskFinished réussi`() {
        val bus = BusCollecteur()
        pont(bus).statusChanged(
            FinTache(DescripteurTache(":app:saluer"), Reussite),
        )
        val evenement = bus.evenements.single() as TaskFinished
        assertEquals(":app:saluer", evenement.taskPath)
        assertEquals(true, evenement.succeeded)
    }

    @Test
    fun `une tâche sautée est une fin réussie`() {
        val bus = BusCollecteur()
        pont(bus).statusChanged(
            FinTache(DescripteurTache(":app:tests"), Saute),
        )
        assertEquals(true, (bus.evenements.single() as TaskFinished).succeeded)
    }

    @Test
    fun `l'échec d'une tâche publie TaskFinished en échec`() {
        val bus = BusCollecteur()
        pont(bus).statusChanged(
            FinTache(DescripteurTache(":app:compileJava"), EchecResultat(listOf(Echec("';' attendu")))),
        )
        assertEquals(false, (bus.evenements.single() as TaskFinished).succeeded)
    }

    @Test
    fun `un évènement hors tâche est ignoré silencieusement`() {
        val bus = BusCollecteur()
        pont(bus).statusChanged(EvenementEtranger())
        assertTrue(bus.evenements.isEmpty())
    }

    @Test
    fun `un chemin de tâche absent est ignoré sans erreur`() {
        val bus = BusCollecteur()
        pont(bus).statusChanged(DemarrageTache(DescripteurTache(null)))
        assertTrue(bus.evenements.isEmpty())
    }
}
