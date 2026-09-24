package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.TaskFinished
import jo.codeide.tooling.protocol.TaskStarted
import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationResult
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.SkippedResult
import org.gradle.tooling.events.StartEvent
import org.gradle.tooling.events.SuccessResult
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.task.TaskProgressEvent

/**
 * Pont évènements de tâches Gradle → protocole (§4.3) : enregistré avec
 * `OperationType.TASK` uniquement — les [TaskStarted]/[TaskFinished] sont
 * les événements que l'UI affiche ; la progression générique
 * (`ProgressEvent` du protocole) est réservée aux types d'opérations que
 * G5 jugera utiles d'ajouter.
 *
 * Appelé depuis les fils internes de Gradle : [EventBus.publier] est
 * bloquant borné, jamais perdant — fil de pompage bloqué = contrôle de flux.
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — les clauses de garde
 * (hors tâche, descripteur absent, chemin absent) sont des sorties précoces
 * documentées, les imbriquer transformerait un filtre clair en pyramide.
 */
@Suppress("ReturnCount")
internal class ProgressBridge(
    private val buildId: String,
    private val bus: EventBus,
) : ProgressListener {
    override fun statusChanged(evenement: org.gradle.tooling.events.ProgressEvent) {
        val tache = evenement as? TaskProgressEvent ?: return
        val descripteur = tache.descriptor as? TaskOperationDescriptor ?: return
        val chemin = descripteur.taskPath ?: return

        when (evenement) {
            is StartEvent -> {
                bus.publier(
                    TaskStarted(
                        id = nouvelId(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        buildId = buildId,
                        taskPath = chemin,
                    ),
                )
            }

            is FinishEvent -> {
                val resultat = evenement.result
                bus.publier(
                    TaskFinished(
                        id = nouvelId(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        buildId = buildId,
                        taskPath = chemin,
                        succeeded = resultat.reussi(),
                    ),
                )
            }

            else -> {
                Unit
            }
        }
    }

    /** Une tâche sautée n'est pas un échec (§3.2 : réussie ou non). */
    private fun OperationResult.reussi(): Boolean = this is SuccessResult || this is SkippedResult
}
