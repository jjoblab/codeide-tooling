package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.BuildRequest
import jo.codeide.tooling.protocol.BuildStarted
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.StreamKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ResultHandler
import org.gradle.tooling.events.OperationType
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Exécution des builds (§4.3) : pont callbacks Tooling API → coroutines par
 * [suspendCancellableCoroutine], l'annulation propagée vers le
 * [org.gradle.tooling.CancellationTokenSource] du build — aucun pool de fils
 * dédié pour cette méthode (Gradle gère son propre threading interne, §4.3).
 *
 * La sortie standard/erreur est diffusée ligne à ligne
 * ([StreamingOutputStream]), les tâches démarrent/finissent en événements
 * ([ProgressBridge]) — le tout sur l'[EventBus], jamais conflaté.
 */
internal class BuildHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
) {
    /** Jetons d'annulation des builds en vol, par identifiant de build. */
    private val annulations = ConcurrentHashMap<String, CancellationTokenSource>()

    /**
     * Lance le build de la requête et publie son cycle complet :
     * [BuildStarted] à l'acceptation, lignes et tâches en cours de route,
     * [BuildFinished] au terme (succès, échec, annulation ou délai).
     *
     * Exemption detekt ciblée (règle 16) : SpreadOperator — la Tooling API
     * n'expose `forTasks` qu'en vararg (aucune surcharge `Iterable<String>`),
     * l'éclatement de la liste est l'unique option.
     */
    @Suppress("SpreadOperator")
    suspend fun lancer(
        requete: BuildRequest,
        delaiMs: Long = TimeoutsServeur.BUILD_MS,
    ) {
        val jeton = GradleConnector.newCancellationTokenSource()
        annulations[requete.buildId] = jeton
        val debut = System.currentTimeMillis()
        bus.publier(
            BuildStarted(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                buildId = requete.buildId,
                tasks = requete.tasks,
            ),
        )
        try {
            val connexion = pool.connexion(File(requete.projectDir))
            withTimeout(delaiMs) {
                suspendCancellableCoroutine { suite ->
                    val lanceur =
                        connexion
                            .newBuild()
                            // Exemption detekt ciblée (règle 16) : SpreadOperator —
                            // la Tooling API n'expose `forTasks` qu'en vararg
                            // (aucune surcharge Iterable<String>), l'éclatement
                            // de la liste est l'unique option.
                            .forTasks(*requete.tasks.toTypedArray())
                            .withArguments(requete.arguments)
                            .setStandardOutput(
                                StreamingOutputStream(requete.buildId, StreamKind.STDOUT, bus),
                            ).setStandardError(
                                // G5 : la sortie d'erreur porte AUSSI les
                                // diagnostics de compilation (javac/kotlinc y
                                // écrivent leurs positions, pas dans le
                                // message d'échec final).
                                StreamingOutputStream(
                                    requete.buildId,
                                    StreamKind.STDERR,
                                    bus,
                                    observateur = ::publierDiagnostics,
                                ),
                            ).addProgressListener(ProgressBridge(requete.buildId, bus), OperationType.TASK)
                            .withCancellationToken(jeton.token())
                    suite.invokeOnCancellation { jeton.cancel() }
                    lanceur.run(handlerResultat(suite))
                }
            }
            publierFin(requete.buildId, reussi = true, debut, null)
        } catch (delai: TimeoutCancellationException) {
            Journal.warn("build ${requete.buildId} : garde de $delaiMs ms dépassée (${delai.message})")
            jeton.cancel()
            publierFin(requete.buildId, reussi = false, debut, "délai de $delaiMs ms dépassé")
        } catch (annulation: CancellationException) {
            jeton.cancel()
            publierFin(requete.buildId, reussi = false, debut, "annulé")
            throw annulation
        } catch (echec: GradleConnectionException) {
            publierFin(
                requete.buildId,
                reussi = false,
                debut,
                messageDEchec(echec),
            )
        } catch (t: Throwable) {
            publierFin(requete.buildId, reussi = false, debut, messageDEchec(t))
        } finally {
            annulations.remove(requete.buildId)
        }
    }

    /**
     * Annule le build [buildId] : `true` si un build en vol a été annulé
     * (l'effet se voit dans son [BuildFinished] final), `false` si aucun
     * build actif ne porte cet identifiant.
     */
    fun annuler(buildId: String): Boolean {
        val jeton = annulations[buildId] ?: return false
        jeton.cancel()
        return true
    }

    /** Toutes les annulations en vol (arrêt propre du serveur). */
    fun toutAnnuler() {
        annulations.values.forEach { it.cancel() }
    }

    /**
     * Publie le diagnostic extrait d'une ligne de stderr (G5) — une ligne
     * non reconnue (contexte, carets…) est simplement ignorée.
     */
    private fun publierDiagnostics(ligne: String) {
        ParseurDiagnostics.analyser(ligne)?.let { diagnostic ->
            bus.publier(diagnostic)
        }
    }

    /** Handler Tooling API → coroutine : la fin du build reprend la suite. */
    private fun handlerResultat(suite: kotlinx.coroutines.CancellableContinuation<Unit>): ResultHandler<Void> =
        object : ResultHandler<Void> {
            override fun onComplete(resultat: Void?) {
                suite.resume(Unit)
            }

            override fun onFailure(echec: GradleConnectionException) {
                suite.resumeWithException(echec)
            }
        }

    private fun publierFin(
        buildId: String,
        reussi: Boolean,
        debut: Long,
        message: String?,
    ) {
        bus.publier(
            BuildFinished(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                buildId = buildId,
                succeeded = reussi,
                durationMs = System.currentTimeMillis() - debut,
                failureMessage = message,
            ),
        )
    }

    /** Message d'échec lisible : la première cause qui explique quelque chose. */
    private fun messageDEchec(echec: Throwable): String {
        var curseur: Throwable = echec
        var message = curseur.message ?: echec::class.simpleName ?: "échec du build"
        // Les échecs Gradle s'emballent en chaînes de causes bavardes et
        // répétitives : la CAUSE profonde porte l'explication utile
        // (ex. « compilation Java échouée »), le wrapper ne dit rien.
        repeat(PROFONDEUR_CAUSES) {
            val cause = curseur.cause ?: return message
            if (cause.message != null && cause.message != curseur.message) {
                message = cause.message!!
            }
            curseur = cause
        }
        return message
    }

    private companion object {
        /** Bornage du rabotage des causes (§3.2 : message humain, concis). */
        const val PROFONDEUR_CAUSES = 4
    }
}
