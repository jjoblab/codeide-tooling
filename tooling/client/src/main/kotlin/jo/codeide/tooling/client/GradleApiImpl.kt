package jo.codeide.tooling.client

import jo.codeide.core.domain.ClasspathProjet
import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EntreeClasspath
import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.EtatSyncTooling
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.domain.InfoTache
import jo.codeide.core.domain.InstantaneTas
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.ModuleClasspath
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.SeveriteDiagnostic
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.TypeEntreeClasspath
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.ToolingReason
import jo.codeide.core.model.AppResult
import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.BuildRequest
import jo.codeide.tooling.protocol.BuildStarted
import jo.codeide.tooling.protocol.CancelRequest
import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathRequest
import jo.codeide.tooling.protocol.ClasspathResult
import jo.codeide.tooling.protocol.DependenciesResult
import jo.codeide.tooling.protocol.Diagnostic
import jo.codeide.tooling.protocol.DiagnosticSeverity
import jo.codeide.tooling.protocol.ErrorCode
import jo.codeide.tooling.protocol.ErrorResponse
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HeapEvent
import jo.codeide.tooling.protocol.HelloResponse
import jo.codeide.tooling.protocol.PartialSyncResult
import jo.codeide.tooling.protocol.PongMessage
import jo.codeide.tooling.protocol.ProgressEvent
import jo.codeide.tooling.protocol.StreamKind
import jo.codeide.tooling.protocol.SyncRequest
import jo.codeide.tooling.protocol.SyncResult
import jo.codeide.tooling.protocol.SyncStarted
import jo.codeide.tooling.protocol.TaskFinished
import jo.codeide.tooling.protocol.TaskInfo
import jo.codeide.tooling.protocol.TaskStarted
import jo.codeide.tooling.protocol.TasksRequest
import jo.codeide.tooling.protocol.TasksResult
import jo.codeide.tooling.protocol.ToolingEvent
import jo.codeide.tooling.protocol.ToolingRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de référence de [GradleToolingRepository] (§5.3).
 *
 * Architecture (§5.2) : la sortie des builds voyage dans des **canaux
 * bornés 4096 à envoi suspendant** — jamais `DROP_OLDEST`, jamais de
 * conflation : perdre une ligne de log de build serait trompeur. Les états
 * (build, tas, connexion) sont des `StateFlow` : conflation LÉGITIME, seul
 * l'état courant compte.
 *
 * Corrélation (§3.2) : chaque requête porte un identifiant, la réponse
 * l'échoie — un livre de promesses résout les requêtes en attente ;
 * [ErrorResponse] porte en plus [ErrorResponse.requestId].
 *
 * Le lancement du process orchestrateur appartient au daemon (G4) : la
 * session s'ouvre ici par [ouvrirSession] ; sans session, les opérations
 * renvoient des échecs typés de connexion — jamais de blocage silencieux.
 *
 * Visibilité publique depuis G4 : le daemon (`tooling:daemon`) consomme
 * [ouvrirSession]/[fermerSession] pour y injecter les sessions acceptées,
 * [marquerEnConnexion]/[marquerEchouee] pour animer les états intermédiaires
 * du port (ADR 0041 décision 8) et [dernierPongMs] pour son health check
 * ping/pong (§5.4) — les pongs arrivent dans le flux d'événements pompé
 * ici, c'est donc cette classe qui tient le repère à jour.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — les surcharges
 * viennent du contrat [GradleToolingRepository] (§5.3 du prompt Tooling),
 * le reste sont les traductions protocol → domaine et la plomberie de
 * session, chacune testée ; les découper en classes artificielles
 * casserait le dialogue requête/réponse qu'elles partagent.
 */
@Suppress("TooManyFunctions")
@Singleton
class GradleApiImpl
    @Inject
    constructor() : GradleToolingRepository {
        private val connexion = MutableStateFlow(EtatConnexion.DECONNECTEE)
        private val tas = MutableStateFlow(InstantaneTas(0, 0))
        private val diagnosticsGlobal = MutableStateFlow<List<DiagnosticBuild>>(emptyList())

        /** État de sync annoncé par l'orchestrateur (étape 32, ADR 0057). */
        private val sync = MutableStateFlow(EtatSyncTooling())

        /**
         * Horodatage (epoch ms) du dernier [PongMessage] reçu — 0 si aucun.
         *
         * Health check du daemon (§5.4) : il sonde l'orchestrateur par
         * `PingMessage` toutes les 5 s et le déclare muet si ce repère
         * vieillit au-delà du délai de 15 s. Les pongs transitent dans le
         * flux d'événements pompé ici — le repère est initialisé à
         * l'ouverture de la session (un orchestrateur qui vient de négocier
         * son handshake est vivant MAINTENANT : le premier contrôle a une
         * base saine).
         */
        val dernierPongMs = MutableStateFlow(0L)

        /**
         * Valeur courante de l'état de connexion — accès direct (sans flux)
         * pour le daemon et les tests : [observeConnectionState] reste la
         * voie d'abonnement de l'UI.
         */
        val etatConnexion: EtatConnexion
            get() = connexion.value

        /** Sorties par build — canal borné, envoi suspendant (§5.2). */
        private val sorties = ConcurrentHashMap<String, Channel<LigneSortieBuild>>()

        /** États par build — conflation légitime (état courant). */
        private val etats = ConcurrentHashMap<String, MutableStateFlow<EtatBuild>>()

        /** Promesses de réponse, par identifiant de requête. */
        private val promesses = ConcurrentHashMap<String, CompletableDeferred<ToolingEvent>>()

        @Volatile
        private var session: SessionTooling? = null

        private var porteePompe: CoroutineScope? = null

        /**
         * Ouvre (ou remplace) la session — appelé par le daemon (G4).
         *
         * La session réelle lit un flux FROID sur socket : les frames entrent
         * dans le tampon de l'OS jusqu'à la première collecte — l'abonnement
         * du pompe ne peut rien perdre (contrairement à un flux chaud à rejeu
         * nul, dont les émissions pré-abonnement disparaîtraient).
         */
        fun ouvrirSession(nouvelleSession: SessionTooling) {
            fermerSession()
            session = nouvelleSession
            connexion.value = EtatConnexion.CONNECTEE
            // Repère initial du health check (§5.4) : voir [dernierPongMs].
            dernierPongMs.value = System.currentTimeMillis()
            val portee = CoroutineScope(SupervisorJob())
            porteePompe = portee
            portee.launch {
                try {
                    nouvelleSession.evenements.collect { pomper(it) }
                } finally {
                    // Fin du flux = déconnexion (EOF ou perte, §5.2/§7.5) —
                    // MAIS seulement si cette session est TOUJOURS la
                    // courante : le finally d'un pompe annulé (remplacement
                    // de session) s'exécute de façon asynchrone et ne doit
                    // pas casser l'état de celle qui l'a remplacée (course
                    // attrapée par le test « ouvrir une nouvelle session
                    // ferme la précédente »).
                    if (session === nouvelleSession) {
                        connexion.value = EtatConnexion.DECONNECTEE
                        session = null
                        romprePromesses()
                        rompreBuildsEnCours()
                        rompreSyncEnCours()
                    }
                }
            }
        }

        /**
         * Publie l'état intermédiaire `EN_CONNEXION` — appelé par le daemon
         * (G4) avant de lancer le process : l'UI qui observe
         * [observeConnectionState] distingue « pas d'orchestrateur » et
         * « orchestrateur en cours de démarrage » (ADR 0041 décision 8).
         */
        fun marquerEnConnexion() {
            connexion.value = EtatConnexion.EN_CONNEXION
        }

        /**
         * Publie l'état terminal `ECHOUEE` — appelé par le daemon (G4) quand
         * les tentatives de (re)démarrage sont épuisées (§5.4, bornage à
         * `MAX_RECONNECT_ATTEMPTS`) : échec définitif jusqu'à un nouvel
         * appel de démarrage.
         */
        fun marquerEchouee() {
            connexion.value = EtatConnexion.ECHOUEE
        }

        /** Ferme la session courante (idempotent) et borné son empreinte. */
        fun fermerSession() {
            // Capturée AVANT l'annulation du pompe : son finally s'exécute
            // de façon asynchrone et peut mettre session à null avant cette
            // ligne — capturer d'abord rend la fermeture déterministe
            // (course attrapée par le test « ouvrir une nouvelle session
            // ferme la précédente »).
            val courante = session
            porteePompe?.let { portee ->
                portee.coroutineContext[Job]?.cancel()
            }
            porteePompe = null
            session = null
            courante?.fermer()
            connexion.value = EtatConnexion.DECONNECTEE
            romprePromesses()
            rompreSyncEnCours()
            // Les canaux fermés gardaient l'historique des builds de la
            // session : elle est finie, l'empreinte s'arrête ici.
            sorties.values.forEach { canal -> runCatching { canal.close() } }
            sorties.clear()
            etats.clear()
        }

        /**
         * Conclut une sync EN COURS à la perte de session (étape 32) :
         * plus aucun résultat n'arrivera — l'état observé repasse au
         * repos, l'UI ne reste pas suspendue sur un « en cours » mort.
         */
        private fun rompreSyncEnCours() {
            if (sync.value.enCours) {
                sync.value = EtatSyncTooling(enCours = false)
            }
        }

        /**
         * Routage des événements de synchronisation (étape 32, ADR 0057) :
         * le départ annoncé PAR le serveur alimente l'état observable — la
         * promesse, elle, attend toujours le résultat.
         */
        private fun pomperSync(evenement: ToolingEvent) {
            when (evenement) {
                is SyncStarted -> {
                    sync.value = EtatSyncTooling(enCours = true, projectDir = evenement.projectDir)
                }

                is SyncResult -> {
                    sync.value = EtatSyncTooling(enCours = false, projectDir = evenement.projectDir)
                    promesses.remove(evenement.id)?.complete(evenement)
                }

                is PartialSyncResult -> {
                    sync.value = EtatSyncTooling(enCours = false, projectDir = evenement.projectDir)
                    promesses.remove(evenement.id)?.complete(evenement)
                }

                else -> {
                    // Séparé du routage principal pour la complexité —
                    // inatteignable : le site d'appel filtre les trois types.
                    Unit
                }
            }
        }

        /** Routage d'un événement entrant vers ses flux et promesses. */
        private suspend fun pomper(evenement: ToolingEvent) {
            when (evenement) {
                is BuildOutput -> {
                    pomperSortie(evenement)
                }

                is BuildStarted -> {
                    pomperDemarrage(evenement)
                }

                is BuildFinished -> {
                    pomperFin(evenement)
                }

                is HeapEvent -> {
                    tas.value = InstantaneTas(moUtilises = evenement.usedMb, moMax = evenement.maxMb)
                }

                is Diagnostic -> {
                    diagnosticsGlobal.value = listOf(evenement.versDiagnosticDomaine())
                }

                is SyncStarted, is SyncResult, is PartialSyncResult -> {
                    pomperSync(evenement)
                }

                is TasksResult -> {
                    promesses.remove(evenement.id)?.complete(evenement)
                }

                is DependenciesResult -> {
                    promesses.remove(evenement.id)?.complete(evenement)
                }

                is ClasspathResult -> {
                    promesses.remove(evenement.id)?.complete(evenement)
                }

                is ErrorResponse -> {
                    promesses.remove(evenement.requestId)?.complete(evenement)
                }

                is PongMessage -> {
                    // Repère du health check du daemon (§5.4) : la réponse
                    // de santé rafraîchit l'horodatage observé.
                    dernierPongMs.value = System.currentTimeMillis()
                }

                // santé pilotée par le daemon (G4)

                is HelloResponse -> {
                    Unit
                }

                // réponse de handshake déjà consommée

                // Événements de granularité tâche et progression générique :
                // l'état exposé est celui du BUILD (§5.3) — G5 affine s'il
                // expose les tâches à l'UI.
                is TaskStarted, is TaskFinished, is ProgressEvent -> {
                    Unit
                }
            }
        }

        // ------------------------------------------------------------------
        // Diffusion (§5.2/§5.3).
        // ------------------------------------------------------------------

        override fun observeBuildOutput(buildId: String): Flow<LigneSortieBuild> = sortie(buildId).receiveAsFlow()

        override fun observeBuildState(buildId: String): Flow<EtatBuild> = etat(buildId).asStateFlow()

        override fun observeHeap(): Flow<InstantaneTas> = tas.asStateFlow()

        override fun observeConnectionState(): Flow<EtatConnexion> = connexion.asStateFlow()

        override fun observeSyncState(): Flow<EtatSyncTooling> = sync.asStateFlow()

        override fun observeDiagnostics(projectDir: File): Flow<List<DiagnosticBuild>> = diagnosticsGlobal.asStateFlow()

        // ------------------------------------------------------------------
        // Opérations (§5.3).
        // ------------------------------------------------------------------

        override suspend fun synchroniser(projectDir: File): AppResult<ResultatSynchronisation> {
            val reponse =
                echanger(
                    SyncRequest(
                        id = nouvelIdentifiant(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = projectDir.canonicalPath,
                    ),
                    delaiMs = DELAI_SYNC_MS,
                ) ?: return echecConnexion()
            return when (reponse) {
                is SyncResult -> {
                    AppResult.Success(
                        ResultatSynchronisation(
                            projectDir = reponse.projectDir,
                            reussie = reponse.succeeded,
                            dureeMs = reponse.durationMs,
                            messageEchec = reponse.failureMessage,
                        ),
                    )
                }

                is PartialSyncResult -> {
                    AppResult.Success(
                        ResultatSynchronisation(
                            projectDir = reponse.projectDir,
                            reussie = false,
                            partielle = true,
                            modelesResolus = reponse.resolvedModels,
                            modelesEchoues = reponse.failedModels,
                        ),
                    )
                }

                is ErrorResponse -> {
                    AppResult.Failure(reponse.versErreurDomaine())
                }

                else -> {
                    AppResult.Failure(
                        AppError.Tooling(ToolingReason.Internal, "réponse inattendue : ${reponse::class.simpleName}"),
                    )
                }
            }
        }

        override suspend fun taches(projectDir: File): AppResult<List<InfoTache>> {
            val reponse =
                echanger(
                    TasksRequest(
                        id = nouvelIdentifiant(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = projectDir.canonicalPath,
                    ),
                    delaiMs = DELAI_TACHES_MS,
                ) ?: return echecConnexion()
            return when (reponse) {
                is TasksResult -> {
                    AppResult.Success(
                        reponse.tasks.map { tache: TaskInfo ->
                            InfoTache(chemin = tache.path, groupe = tache.group, nomAffiche = tache.displayName)
                        },
                    )
                }

                is ErrorResponse -> {
                    AppResult.Failure(reponse.versErreurDomaine())
                }

                else -> {
                    AppResult.Failure(
                        AppError.Tooling(ToolingReason.Internal, "réponse inattendue : ${reponse::class.simpleName}"),
                    )
                }
            }
        }

        override suspend fun classpath(projectDir: File): AppResult<ClasspathProjet> {
            val reponse =
                echanger(
                    ClasspathRequest(
                        id = nouvelIdentifiant(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = projectDir.canonicalPath,
                    ),
                    delaiMs = DELAI_CLASSPATH_MS,
                ) ?: return echecConnexion()
            return when (reponse) {
                is ClasspathResult -> {
                    AppResult.Success(reponse.versClasspathDomaine())
                }

                is ErrorResponse -> {
                    AppResult.Failure(reponse.versErreurDomaine())
                }

                else -> {
                    AppResult.Failure(
                        AppError.Tooling(ToolingReason.Internal, "réponse inattendue : ${reponse::class.simpleName}"),
                    )
                }
            }
        }

        override suspend fun build(
            projectDir: File,
            tasks: List<String>,
        ): String {
            val buildId = nouvelIdentifiant()
            // Canal et état créés AVANT l'envoi : les événements qui arrivent
            // PENDANT le lancement (l'orchestrateur émet dès BuildStarted) se
            // tamponnent au lieu d'être perdus — la souscription après le
            // lancement ne manque rien (§5.2).
            sortie(buildId)
            etat(buildId).value = EtatBuild(buildId = buildId, statut = StatutBuild.EN_COURS)
            val sessionCourante = session
            if (sessionCourante == null) {
                // Pas d'exception silencieuse : l'état du build porte l'échec,
                // l'UI qui observe [observeBuildState] le voit immédiatement.
                etat(buildId).value =
                    EtatBuild(
                        buildId = buildId,
                        statut = StatutBuild.ECHOUE,
                        messageEchec = "orchestrateur non connecté",
                    )
                return buildId
            }
            try {
                sessionCourante.envoyer(
                    BuildRequest(
                        id = nouvelIdentifiant(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = projectDir.canonicalPath,
                        tasks = tasks,
                        buildId = buildId,
                    ),
                )
            } catch (perdue: java.io.IOException) {
                etat(buildId).value =
                    EtatBuild(
                        buildId = buildId,
                        statut = StatutBuild.ECHOUE,
                        messageEchec = "envoi impossible : ${perdue.message}",
                    )
            }
            return buildId
        }

        override fun cancel(buildId: String) {
            val sessionCourante = session ?: return
            // Feu-and-forget sur la portée du pompe : cancel() n'est pas
            // suspendu (contrat §5.3) et ne doit jamais bloquer l'appelant.
            porteePompe?.launch {
                runCatching {
                    sessionCourante.envoyer(
                        CancelRequest(
                            id = nouvelIdentifiant(),
                            protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                            buildId = buildId,
                        ),
                    )
                }
            }
        }

        // ------------------------------------------------------------------
        // Intérieur.
        // ------------------------------------------------------------------

        /**
         * Envoie une requête et attend SA réponse (promesse + délai).
         *
         * Exemptions detekt ciblées (règle 16) : ReturnCount — clauses de
         * garde (absence de session, échec d'envoi) retournant chacune une
         * valeur typée ; SwallowedException — l'échec d'envoi est ATTENDU
         * et traduit en échec de connexion par l'appelant, le délai devient
         * [ErrorResponse] typé (l'exception sert de message), jamais avalés
         * silencieusement.
         */
        @Suppress("ReturnCount", "SwallowedException")
        private suspend fun echanger(
            requete: ToolingRequest,
            delaiMs: Long,
        ): ToolingEvent? {
            val sessionCourante = session ?: return null
            val promesse = CompletableDeferred<ToolingEvent>()
            promesses[requete.id] = promesse
            try {
                sessionCourante.envoyer(requete)
            } catch (perdue: java.io.IOException) {
                promesses.remove(requete.id)
                return null
            }
            return try {
                withTimeout(delaiMs) { promesse.await() }
            } catch (delaiDepasse: TimeoutCancellationException) {
                promesses.remove(requete.id)
                ErrorResponse(
                    id = requete.id,
                    protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                    requestId = requete.id,
                    code = ErrorCode.TIMEOUT,
                    message = "délai de $delaiMs ms dépassé (${delaiDepasse.message ?: "timeout"})",
                )
            }
        }

        private fun echecConnexion(): AppResult<Nothing> =
            AppResult.Failure(
                AppError.Tooling(ToolingReason.ConnectionLost, "orchestrateur non connecté"),
            )

        private fun romprePromesses() {
            promesses.values.forEach { promesse ->
                promesse.complete(
                    ErrorResponse(
                        id = "connexion-perdue",
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        requestId = "connexion-perdue",
                        code = ErrorCode.CONNECTION_LOST,
                        message = "connexion avec l'orchestrateur perdue",
                    ),
                )
            }
            promesses.clear()
        }

        /**
         * Conclut les builds EN COURS à la perte de session (§7.5 : process
         * tué en plein build, socket perdue) — sans cela un build orphelin
         * resterait EN_COURS à jamais : aucun [BuildFinished] n'arrivera
         * plus, l'état observé par l'UI ne changerait plus et le canal de
         * sortie resterait ouvert (collecteur suspendu indéfiniment).
         * Même sémantique de fermeture que [pomperFin] : l'état porte
         * l'échec, le canal se ferme — un collecteur tardif draine le
         * tampon puis complète.
         */
        private fun rompreBuildsEnCours() {
            etats.values
                .filter { etatBuild -> etatBuild.value.statut == StatutBuild.EN_COURS }
                .forEach { etatBuild ->
                    val buildId = etatBuild.value.buildId
                    etatBuild.value =
                        EtatBuild(
                            buildId = buildId,
                            statut = StatutBuild.ECHOUE,
                            messageEchec = "connexion avec l'orchestrateur perdue",
                        )
                    sorties[buildId]?.close()
                }
        }

        private fun sortie(buildId: String): Channel<LigneSortieBuild> =
            sorties.computeIfAbsent(buildId) { Channel(TAILLE_TAMPON_SORTIE) }

        private fun etat(buildId: String): MutableStateFlow<EtatBuild> =
            etats.computeIfAbsent(buildId) {
                MutableStateFlow(EtatBuild(buildId = buildId, statut = StatutBuild.EN_COURS))
            }

        /** Une ligne de sortie arrive : elle part dans le canal du build. */
        private suspend fun pomperSortie(evenement: BuildOutput) {
            sorties[evenement.buildId]?.send(
                LigneSortieBuild(
                    buildId = evenement.buildId,
                    flux = evenement.stream.versFluxDomaine(),
                    ligne = evenement.line,
                    horodatageMs = evenement.timestampMs,
                ),
            )
        }

        /** Le build démarre : son état passe à EN_COURS. */
        private fun pomperDemarrage(evenement: BuildStarted) {
            etat(evenement.buildId).value =
                EtatBuild(buildId = evenement.buildId, statut = StatutBuild.EN_COURS)
        }

        /**
         * Le build se termine : état final, puis le canal se FERME sans être
         * retiré — un collecteur tardif (onglet Sortie ouvert après le build,
         * rotation) draine le tampon puis complète : aucune perte, et
         * ré-observer un build terminé rejoue son historique. La mémoire est
         * bornée par la durée de la SESSION : [fermerSession] nettoie tout.
         */
        private fun pomperFin(evenement: BuildFinished) {
            etat(evenement.buildId).value =
                EtatBuild(
                    buildId = evenement.buildId,
                    statut = if (evenement.succeeded) StatutBuild.REUSSI else StatutBuild.ECHOUE,
                    dureeMs = evenement.durationMs,
                    messageEchec = evenement.failureMessage,
                )
            sorties[evenement.buildId]?.close()
        }

        private fun nouvelIdentifiant(): String = UUID.randomUUID().toString()

        private fun StreamKind.versFluxDomaine(): FluxSortieBuild =
            when (this) {
                StreamKind.STDOUT -> FluxSortieBuild.STDOUT
                StreamKind.STDERR -> FluxSortieBuild.STDERR
            }

        private fun Diagnostic.versDiagnosticDomaine(): DiagnosticBuild =
            DiagnosticBuild(
                severite =
                    when (severity) {
                        DiagnosticSeverity.ERROR -> SeveriteDiagnostic.ERREUR
                        DiagnosticSeverity.WARNING -> SeveriteDiagnostic.AVERTISSEMENT
                        DiagnosticSeverity.INFO -> SeveriteDiagnostic.INFO
                    },
                fichier = file,
                ligne = line,
                colonne = column,
                message = message,
                source = source,
            )

        /** Traduit un classpath du protocole vers le domaine (ADR 0058). */
        private fun ClasspathResult.versClasspathDomaine(): ClasspathProjet =
            ClasspathProjet(
                projectDir = projectDir,
                modules =
                    modules.map { module ->
                        ModuleClasspath(
                            nom = module.name,
                            dossiersSources = module.sourceDirs,
                            entrees =
                                module.entries.map { entree ->
                                    EntreeClasspath(
                                        chemin = entree.path,
                                        type =
                                            when (entree.kind) {
                                                ClasspathKind.JAR -> TypeEntreeClasspath.JAR
                                                ClasspathKind.AAR -> TypeEntreeClasspath.AAR
                                                ClasspathKind.DOSSIER -> TypeEntreeClasspath.DOSSIER
                                                ClasspathKind.MODULE -> TypeEntreeClasspath.MODULE
                                            },
                                        portee = entree.scope,
                                        sources = entree.sources,
                                    )
                                },
                        )
                    },
            )

        private fun ErrorResponse.versErreurDomaine(): AppError.Tooling =
            AppError.Tooling(
                code =
                    when (code) {
                        ErrorCode.PROTOCOL_VERSION_MISMATCH -> ToolingReason.ProtocolVersion
                        ErrorCode.HANDSHAKE_FAILED -> ToolingReason.Handshake
                        ErrorCode.UNKNOWN_REQUEST -> ToolingReason.UnknownRequest
                        ErrorCode.FRAME_TOO_LARGE, ErrorCode.MALFORMED_FRAME -> ToolingReason.Frame
                        ErrorCode.TIMEOUT -> ToolingReason.Timeout
                        ErrorCode.CONNECTION_LOST -> ToolingReason.ConnectionLost
                        ErrorCode.BUILD_LAUNCH_FAILED -> ToolingReason.BuildLaunch
                        ErrorCode.INTERNAL_ERROR -> ToolingReason.Internal
                    },
                message = message,
            )

        private companion object {
            /**
             * Tampon de sortie par build (§5.2 : 4096, envoi suspendant —
             * jamais conflaté, jamais perdant).
             */
            const val TAILLE_TAMPON_SORTIE = 4096

            /** Délais client des requêtes-réponses (§7.5). */
            const val DELAI_SYNC_MS: Long = 5 * 60_000L
            const val DELAI_TACHES_MS: Long = 30_000L

            /** Délai client du classpath LSP (ADR 0058, aligné serveur). */
            const val DELAI_CLASSPATH_MS: Long = 5 * 60_000L
        }
    }
