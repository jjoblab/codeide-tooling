package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.BuildRequest
import jo.codeide.tooling.protocol.CancelRequest
import jo.codeide.tooling.protocol.ClasspathRequest
import jo.codeide.tooling.protocol.DependenciesRequest
import jo.codeide.tooling.protocol.ErrorCode
import jo.codeide.tooling.protocol.ErrorResponse
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HeapRequest
import jo.codeide.tooling.protocol.HelloRequest
import jo.codeide.tooling.protocol.ModelRequest
import jo.codeide.tooling.protocol.PingMessage
import jo.codeide.tooling.protocol.PongMessage
import jo.codeide.tooling.protocol.ProtocolJson
import jo.codeide.tooling.protocol.SyncRequest
import jo.codeide.tooling.protocol.TasksRequest
import jo.codeide.tooling.protocol.ToolingRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import java.io.EOFException
import java.io.IOException

/**
 * Boucle de réception et aiguillage (§4.5) : la lecture bloque le fil
 * dédié (le principal de [ServerMain]), chaque requête est traitée dans
 * une coroutine d'une portée bornée (`limitedParallelism(6)`) — jamais de
 * traitement en ligne, la lecture continue pendant les builds.
 *
 * Fin de connexion PROPRE ([EOFException]) distinguée de la corruption
 * (frames typées du [FrameCodec]) : l'une arrête le serveur silencieusement,
 * l'autre est journalisée — le [Journal] alimente le rapport d'erreur de
 * l'opérateur (§7.5 : pas de blocage silencieux).
 */
internal class MessageDispatcher(
    private val socket: SocketClient,
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
    private val intervalleTasMs: Long,
) {
    /** Portée bornée des requêtes (§4.5 : jamais bloquer la lecture). */
    private val porteeRequetes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(PARALLELISME))

    private val builds = BuildHandler(pool, bus)
    private val synchronisations = SyncHandler(pool, bus)
    private val taches = TasksHandler(pool, bus)
    private val modeles = ModelHandler(pool, bus)
    private val dependances = DependenciesHandler(pool, bus)
    private val classpaths = ClasspathHandler(pool, bus)
    private val tas = HeapMonitor(bus)

    /** Boucle de réception — retourne à la fin de connexion. */
    fun boucle() {
        try {
            while (true) {
                val frame = socket.lireFrame()
                val requete =
                    try {
                        ProtocolJson.decoderRequete(String(frame, Charsets.UTF_8))
                    } catch (inconnue: SerializationException) {
                        Journal.warn("requête indécodable rejetée : ${inconnue.message}")
                        repondreErreur(
                            "inconnue",
                            ErrorCode.UNKNOWN_REQUEST,
                            "message non reconnu par l'orchestrateur : ${inconnue.message}",
                        )
                        continue
                    }
                porteeRequetes.launch { traiter(requete) }
            }
        } catch (fin: EOFException) {
            Journal.info("connexion fermée par l'app (${fin.message}) — arrêt propre")
        } catch (perdue: IOException) {
            Journal.error("connexion perdue : ${perdue.message}")
        } finally {
            arreter()
        }
    }

    /** Aiguillage typé — exhaustif par construction (interface scellée). */
    private suspend fun traiter(requete: ToolingRequest) {
        try {
            when (requete) {
                is BuildRequest -> {
                    builds.lancer(requete)
                }

                is CancelRequest -> {
                    traiterAnnulation(requete)
                }

                is SyncRequest,
                is TasksRequest,
                is DependenciesRequest,
                is ClasspathRequest,
                is ModelRequest,
                -> {
                    traiterModeles(requete)
                }

                is HeapRequest -> {
                    bus.publier(tas.instantane())
                }

                is PingMessage -> {
                    repondrePong(requete)
                }

                // HelloRequest part de l'orchestrateur (§4.1) : en recevoir
                // une de l'app viole le sens du protocole.
                is HelloRequest -> {
                    repondreSensInvalide(requete)
                }
            }
        } catch (delai: TimeoutCancellationException) {
            Journal.warn("délai dépassé (${delai.message}) pour ${requete::class.simpleName}")
            repondreErreur(
                requete.id,
                ErrorCode.TIMEOUT,
                "délai dépassé pour ${requete::class.simpleName}",
            )
        } catch (t: Throwable) {
            Journal.error("échec du traitement de ${requete::class.simpleName} : ${t.message}", t)
            repondreErreur(
                requete.id,
                ErrorCode.INTERNAL_ERROR,
                t.message ?: "échec interne de l'orchestrateur",
            )
        }
    }

    /** Annulation : l'effet se voit dans le BuildFinished du build visé. */
    private fun traiterAnnulation(requete: CancelRequest) {
        if (!builds.annuler(requete.buildId)) {
            repondreErreur(
                requete.id,
                ErrorCode.INTERNAL_ERROR,
                "aucun build actif ne porte l'identifiant ${requete.buildId}",
            )
        }
    }

    /**
     * Aiguillage des requêtes de MODÈLES (§5.3 : sync résiliente, tâches,
     * dépendances, classpath LSP ADR 0058, modèle brut) — chacune sous SA
     * garde de délai (§7.5).
     */
    private suspend fun traiterModeles(requete: ToolingRequest) {
        when (requete) {
            is SyncRequest -> {
                avecDelai(TimeoutsServeur.SYNC_MS, requete.id) {
                    synchronisations.synchroniser(requete)
                }
            }

            is TasksRequest -> {
                avecDelai(TimeoutsServeur.TACHES_MS, requete.id) {
                    taches.taches(requete)
                }
            }

            is DependenciesRequest -> {
                avecDelai(TimeoutsServeur.DEPENDANCES_MS, requete.id) {
                    dependances.dependances(requete)
                }
            }

            is ClasspathRequest -> {
                avecDelai(TimeoutsServeur.CLASSPATH_MS, requete.id) {
                    classpaths.classpath(requete)
                }
            }

            is ModelRequest -> {
                avecDelai(TimeoutsServeur.MODELE_MS, requete.id) {
                    modeles.modele(requete)
                }
            }

            else -> {
                // Inatteignable : [traiter] a déjà filtré la famille entière.
                Unit
            }
        }
    }

    private fun repondrePong(requete: PingMessage) {
        bus.publier(
            PongMessage(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
            ),
        )
    }

    private fun repondreSensInvalide(requete: HelloRequest) {
        repondreErreur(
            requete.id,
            ErrorCode.UNKNOWN_REQUEST,
            "HelloRequest ne peut venir de l'app — c'est l'orchestrateur qui l'émet",
        )
    }

    /** Délai de garde (§7.5) : le dépassement devient [ErrorResponse] TIMEOUT. */
    private suspend fun avecDelai(
        delaiMs: Long,
        idRequete: String,
        traitement: suspend () -> Unit,
    ) {
        try {
            withTimeout(delaiMs) { traitement() }
        } catch (delai: TimeoutCancellationException) {
            Journal.warn("garde de $delaiMs ms dépassée (${delai.message})")
            repondreErreur(
                idRequete,
                ErrorCode.TIMEOUT,
                "délai de $delaiMs ms dépassé",
            )
        }
    }

    private fun repondreErreur(
        idRequete: String,
        code: ErrorCode,
        message: String,
    ) {
        bus.publier(
            ErrorResponse(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                requestId = idRequete,
                code = code,
                message = message,
            ),
        )
    }

    /** Arrêt : annule les traitements en vol, les builds, le tas. */
    fun arreter() {
        builds.toutAnnuler()
        tas.arreter()
        porteeRequetes.cancel()
    }

    /** Surveille le tas dès l'ouverture (§4.6, borné par la configuration). */
    fun demarrerSurveillance() {
        tas.demarrer(intervalleTasMs)
    }

    private companion object {
        /** Parallélisme borné des requêtes (§4.5 : 6 au maximum). */
        const val PARALLELISME = 6
    }
}
