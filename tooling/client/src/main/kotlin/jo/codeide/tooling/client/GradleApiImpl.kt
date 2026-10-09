package jo.codeide.tooling.client

import jo.codeide.core.domain.AccumulateurLatence
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.ClasspathProjet
import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtapeSyncTooling
import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.EtatSyncTooling
import jo.codeide.core.domain.EtatTacheBuild
import jo.codeide.core.domain.EvenementSyncFlux
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.domain.InfoTache
import jo.codeide.core.domain.InstantaneTas
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.LigneSortieSync
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.StatutTache
import jo.codeide.core.domain.TelechargementBuild
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.ToolingReason
import jo.codeide.core.model.AppResult
import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.BuildInput
import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.BuildRequest
import jo.codeide.tooling.protocol.BuildScriptsResult
import jo.codeide.tooling.protocol.BuildStarted
import jo.codeide.tooling.protocol.CancelRequest
import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathRequest
import jo.codeide.tooling.protocol.ClasspathResult
import jo.codeide.tooling.protocol.DependenciesResult
import jo.codeide.tooling.protocol.Diagnostic
import jo.codeide.tooling.protocol.ErrorCode
import jo.codeide.tooling.protocol.ErrorResponse
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HeapEvent
import jo.codeide.tooling.protocol.HelloResponse
import jo.codeide.tooling.protocol.PartialSyncResult
import jo.codeide.tooling.protocol.PongMessage
import jo.codeide.tooling.protocol.ProgressEvent
import jo.codeide.tooling.protocol.SyncOutput
import jo.codeide.tooling.protocol.SyncProgress
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
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withTimeoutOrNull
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
 * Exemption detekt ciblée (règle 16) : TooManyFunctions et LargeClass
 * — les surcharges viennent du contrat [GradleToolingRepository] (§5.3 du
 * prompt Tooling), le reste sont les traductions protocol → domaine et la
 * plomberie de session, chacune testée ; les découper en classes
 * artificielles casserait le dialogue requête/réponse qu'elles partagent.
 * v0.49.0 (ADR 0080) : le routeur et les consommateurs de voies PARTAGENT
 * l'état privé de la session (promesses, canaux, états) — les extraire
 * exigerait d'exposer cet état, précisément ce que la classe borne.
 */
@Suppress("TooManyFunctions", "LargeClass")
@Singleton
class GradleApiImpl
    @Inject
    constructor(
        private val journal: AppLogger,
    ) : GradleToolingRepository {
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

        /**
         * Tâches par build — canal borné, envoi suspendant (v3) : mêmes
         * garanties que la sortie (jamais conflaté, tamponné AVANT le
         * lancement, rejouable par un collecteur tardif jusqu'à la fin du
         * build).
         */
        private val tachesParBuild = ConcurrentHashMap<String, Channel<EtatTacheBuild>>()

        /**
         * Téléchargements par build — canal borné, envoi suspendant (v4,
         * addendum §6) : mêmes garanties que les tâches ; alimenté par les
         * `ProgressEvent` porteurs d'un détail de téléchargement.
         */
        private val telechargementsParBuild = ConcurrentHashMap<String, Channel<TelechargementBuild>>()

        /**
         * Dernier signe de vie de la sync (ms) — le délai d'INACTIVITÉ de
         * [synchroniser] se réarme à chaque événement (v4 §3.1 : un
         * téléchargement qui progresse ne meurt plus à 5 minutes).
         */
        private val derniereActiviteSyncMs =
            java.util.concurrent.atomic
                .AtomicLong(0L)

        /**
         * Délai d'inactivité de la sync (v4 §3.1) — interne mutable : la
         * production attend 90 s SANS événement, les tests le raccourcissent
         * (même patron que les réglages pilotables).
         */
        @Volatile
        internal var delaiInactiviteSyncMs: Long = DELAI_INACTIVITE_SYNC_MS

        /**
         * Latence TRANSPORT des sorties, par build (v0.43.0 — mesure de la
         * console lente) : écart entre l'émission côté orchestrateur
         * (`BuildOutput.timestampMs`, pris par `StreamingOutputStream`) et
         * la réception ICI, juste après décodage de la frame. Le saut qui
         * grandit désigne le goulot : file de l'EventBus serveur (contre-
         * pression), écriture socket, lecture client — la difference avec
         * la latence de publication (GradleService) isole la moitié cliente.
         * Nettoyé à la fin du build.
         */
        private val latencesTransport =
            ConcurrentHashMap<String, AccumulateurLatence>()

        /**
         * Flux de synchronisation COMPLET et ORDONNÉ — canal borné, envoi
         * suspendant (v0.48.0, ADR 0079 ; il fut le canal des seules étapes
         * en v3) : [EvenementSyncFlux.Debut], [EvenementSyncFlux.Ligne] au
         * fil de l'eau, [EvenementSyncFlux.Etape] à chaque transition, puis
         * [EvenementSyncFlux.Terminal] en DERNIER — la pompe y envoie DANS
         * L'ORDRE D'ARRIVÉE des événements du serveur (un seul fil, des
         * `send` séquentiels) : la conclusion du flux ne peut pas précéder
         * les lignes et étapes qu'elle conclut. Unique pour le process : les
         * syncs se succèdent, ne se chevauchent pas (une seule session
         * orchestrateur).
         */
        private val flusSync = Channel<EvenementSyncFlux>(TAILLE_TAMPON_SYNC)

        /**
         * Requêtes de sync EN VOL (v0.48.0) — identifiants des syncs
         * envoyées au serveur : un [ErrorResponse] ou une rupture de session
         * qui répond à l'une d'elles produit un TERMINAL (une UI « en
         * cours » morte est le bug corrigé par ADR 0079 — plus aucun
         * SyncResult n'arrivera, le flux doit conclure lui-même).
         */
        private val syncsEnVol = ConcurrentHashMap.newKeySet<String>()

        /** États par build — conflation légitime (état courant). */
        private val etats = ConcurrentHashMap<String, MutableStateFlow<EtatBuild>>()

        /** Promesses de réponse, par identifiant de requête. */
        private val promesses = ConcurrentHashMap<String, CompletableDeferred<ToolingEvent>>()

        @Volatile
        private var session: SessionTooling? = null

        private var porteePompe: CoroutineScope? = null

        /**
         * Voie BUILD ordonnée de la session courante (v0.49.0, ADR 0080) :
         * le lecteur y dépose les événements de la famille build DANS
         * l'ORDRE D'ARRIVÉE ; SON consommateur fait les envois suspendants
         * vers les canaux aval — une console lente ne peut plus geler la
         * lecture du socket (leçon v0.45.1 : « une pompe unique ne doit
         * JAMAIS se bloquer » — elle ne le peut plus, par construction).
         */
        @Volatile
        private var voieBuildCourante: Channel<ToolingEvent>? = null

        /**
         * Voie SYNC ordonnée de la session courante (v0.49.0) : la famille
         * sync a SA voie — un canal flusSync plein ne retient PLUS les pongs
         * ni les sorties de build (retour terrain v0.47/v0.48 : lignes en
         * rafales 76 s après la fin du build, « orchestrateur muet »).
         */
        @Volatile
        private var voieSyncCourante: Channel<ToolingEvent>? = null

        /** Profondeur courante de la voie build (diagnostic v0.49.0). */
        private val profondeurBuild =
            java.util.concurrent.atomic
                .AtomicLong(0L)

        /** Profondeur courante de la voie sync (diagnostic v0.49.0). */
        private val profondeurSync =
            java.util.concurrent.atomic
                .AtomicLong(0L)

        /** Datation du dernier avertissement de voie saturée (débit borné). */
        private val dernierAvertissementVoieMs =
            java.util.concurrent.atomic
                .AtomicLong(0L)

        /**
         * Ouvre (ou remplace) la session — appelé par le daemon (G4).
         *
         * La session réelle lit un flux FROID sur socket : les frames entrent
         * dans le tampon de l'OS jusqu'à la première collecte — l'abonnement
         * du pompe ne peut rien perdre (contrairement à un flux chaud à rejeu
         * nul, dont les émissions pré-abonnement disparaîtraient).
         *
         * v0.49.0 (ADR 0080) : la pompe unique devient un LECTEUR qui ne
         * suspend JAMAIS hors de la lecture — pong mis à jour en chemin
         * rapide, familles build/sync routées vers DEUX voies ordonnées aux
         * consommateurs dédiés, événements légers traités en ligne.
         *
         * v0.50.0 (ADR 0081) : la session réelle lit dans UN FIL DÉDIÉ hors
         * dispatcheurs ([SessionTooling.acheminerVia]) — le router (non
         * suspendant) s'exécute au POINT DE LECTURE, dans le fil : plus
         * aucun changement de contexte de scheduler entre l'arrivée des
         * octets et le routage. La collecte du flux ne route PLUS (le fil
         * l'a fait avant son `trySend`) — elle reste le signal de fin de
         * flux et son finally garde tout le ménage. Les sessions factices
         * ne supportent pas le fil : la collecte route, comme avant.
         */
        fun ouvrirSession(nouvelleSession: SessionTooling) {
            fermerSession()
            session = nouvelleSession
            connexion.value = EtatConnexion.CONNECTEE
            // Repère initial du health check (§5.4) : voir [dernierPongMs].
            dernierPongMs.value = System.currentTimeMillis()
            val portee = CoroutineScope(SupervisorJob() + DISPATCH_POMPE)
            porteePompe = portee
            val voieBuild = Channel<ToolingEvent>(Channel.UNLIMITED)
            val voieSync = Channel<ToolingEvent>(Channel.UNLIMITED)
            voieBuildCourante = voieBuild
            voieSyncCourante = voieSync
            // v0.50.0 : le router est remis à la session réelle — exécuté par
            // son FIL de lecture, au vrai point de réception (pong, latence
            // de transport, voies) ; les factices routent via la collecte.
            val routeParLeFil =
                nouvelleSession.acheminerVia { evenement ->
                    router(evenement, voieBuild, voieSync)
                }
            // Consommateurs des voies : les envois suspendants vers les
            // canaux aval vivent ICI, isolés du lecteur — une voie pleine
            // retarde SA famille, jamais la santé ni l'autre famille. Une
            // pan imprévue d'un événement ne tue pas la voie (journalisée,
            // l'événement suivant la reprend). Lancés AVANT le lecteur : le
            // `finally` de celui-ci joint leurs Jobs pour drainer les voies
            // avant de conclure (v0.50.0).
            val travailleurBuild =
                portee.launch {
                    for (evenement in voieBuild) {
                        profondeurBuild.decrementAndGet()
                        traiterSansPanne(evenement) { pomperBuild(it) }
                    }
                }
            val travailleurSync =
                portee.launch {
                    for (evenement in voieSync) {
                        profondeurSync.decrementAndGet()
                        traiterSansPanne(evenement) { pomperSyncFamille(it) }
                    }
                }
            portee.launch {
                try {
                    nouvelleSession.evenements.collect {
                        if (!routeParLeFil) {
                            router(it, voieBuild, voieSync)
                        }
                    }
                } finally {
                    // Fin du flux = déconnexion (EOF ou perte, §5.2/§7.5) —
                    // MAIS seulement si cette session est TOUJOURS la
                    // courante : le finally d'un lecteur annulé (remplacement
                    // de session) s'exécute de façon asynchrone et ne doit
                    // pas casser l'état de celle qui l'a remplacée (course
                    // attrapée par le test « ouvrir une nouvelle session
                    // ferme la précédente »).
                    voieBuild.close()
                    voieSync.close()
                    // v0.50.0 : les lignes DÉJÀ ROUTÉES finissent leur chemin
                    // AVANT la conclusion — sans cette fenêtre, la rupture
                    // referme le canal de sortie pendant que la dernière
                    // ligne dort encore dans sa voie : elle meurt au `send`
                    // (course révélée par le test de chaos « une déconnexion
                    // échoue les builds en cours » sur machine chargée : le
                    // consommateur n'avait pas été schedulé entre le routage
                    // et la rupture). Bornée : une console aval morte ne
                    // retarde jamais la conclusion au-delà de la fenêtre —
                    // au délai, on rompt comme avant. Sur remplacement de
                    // session les consommateurs sont déjà annulés : le join
                    // rend la main immédiatement.
                    withTimeoutOrNull(FENETRE_DRAINAGE_VOIES_MS) {
                        travailleurBuild.join()
                        travailleurSync.join()
                    }
                    if (session === nouvelleSession) {
                        connexion.value = EtatConnexion.DECONNECTEE
                        session = null
                        voieBuildCourante = null
                        voieSyncCourante = null
                        romprePromesses()
                        rompreBuildsEnCours()
                        rompreSyncEnCours()
                    }
                }
            }
        }

        /**
         * Signes vitaux de la pompe (v0.49.0, ADR 0080) : âge du dernier
         * pong et profondeur des voies — le daemon les journalise quand il
         * déclare l'orchestrateur muet : la ligne désigne le coupable
         * (voies profondes = console lente côté app, voies vides + pong
         * vieux = orchestrateur réellement mort).
         */
        fun signesVitaux(): String {
            val agePong = (System.currentTimeMillis() - dernierPongMs.value).coerceAtLeast(0)
            return "dernierPong=$agePong ms, voieBuild=${profondeurBuild.get()}, " +
                "voieSync=${profondeurSync.get()}, session=${session != null}"
        }

        /**
         * Traite un événement d'une voie SANS laisser une pan le tuer : la
         * voie survit, l'événement fautif est journalisé — une pompe qui
         * meurt en silence emporte la lecture du socket avec elle (retour
         * terrain v0.48 : plus rien n'arrive, « muet », kill, relance).
         */
        private suspend fun traiterSansPanne(
            evenement: ToolingEvent,
            traitement: suspend (ToolingEvent) -> Unit,
        ) {
            try {
                traitement(evenement)
            } catch (annulation: kotlinx.coroutines.CancellationException) {
                // L'annulation n'est PAS une pan : la relancer, sinon la
                // voie survivrait à la fermeture de session (boucle de
                // captures sans fin — attraper Throwable exige ce rejet).
                throw annulation
            } catch (t: Throwable) {
                journal.e(
                    TAG_POMPE,
                    t,
                ) { "pan de traitement d'un ${evenement::class.simpleName} : ${t.message}" }
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
            // v0.49.0 : les voies de la session morte ne se rejouent pas —
            // les compteurs repartent à zéro pour la session suivante.
            voieBuildCourante = null
            voieSyncCourante = null
            profondeurBuild.set(0L)
            profondeurSync.set(0L)
            courante?.fermer()
            connexion.value = EtatConnexion.DECONNECTEE
            romprePromesses()
            rompreSyncEnCours()
            // Les canaux fermés gardaient l'historique des builds de la
            // session : elle est finie, l'empreinte s'arrête ici.
            sorties.values.forEach { canal -> runCatching { canal.close() } }
            sorties.clear()
            tachesParBuild.values.forEach { canal -> runCatching { canal.close() } }
            tachesParBuild.clear()
            etats.clear()
        }

        /**
         * Conclut une sync EN COURS à la perte de session (étape 32 ;
         * v0.48.0 : le TERMINAL traverse le flux ordonné) : plus aucun
         * résultat n'arrivera — le flux se conclut lui-même (échec de
         * connexion), l'état observable repasse au repos, l'UI ne reste pas
         * suspendue sur un « en cours » mort. `trySend` : la rupture peut
         * survenir hors d'un contexte suspendu — le canal borné (256)
         * absorbe sans attendre.
         */
        private fun rompreSyncEnCours() {
            if (sync.value.enCours) {
                sync.value = EtatSyncTooling(enCours = false)
            }
            if (syncsEnVol.isEmpty()) return
            val echec: AppResult<ResultatSynchronisation> =
                AppResult.Failure(
                    AppError.Tooling(ToolingReason.ConnectionLost, "connexion avec l'orchestrateur perdue"),
                )
            syncsEnVol.forEach { flusSync.trySend(EvenementSyncFlux.Terminal(echec)) }
            syncsEnVol.clear()
        }

        /**
         * Routage des événements de synchronisation (étape 32, ADR 0057 ;
         * v0.48.0, ADR 0079 : suspendu — chaque événement traverse le canal
         * ORDONNÉ avant de toucher à la promesse) : le départ annoncé PAR le
         * serveur alimente le flux ET l'état observable, le résultat termine
         * le flux AVANT de réveiller l'attendeur — l'ordre du câble est
         * l'ordre de la console.
         */
        private suspend fun pomperSync(evenement: ToolingEvent) {
            when (evenement) {
                is SyncStarted -> {
                    flusSync.send(EvenementSyncFlux.Debut(projectDir = evenement.projectDir))
                    sync.value = EtatSyncTooling(enCours = true, projectDir = evenement.projectDir)
                }

                is SyncResult -> {
                    syncsEnVol.remove(evenement.id)
                    sync.value = EtatSyncTooling(enCours = false, projectDir = evenement.projectDir)
                    // v0.48.0 (ADR 0079) : le TERMINAL précède le
                    // réveil de l'attendeur — la vidange conclura l'UI
                    // (console + en-tête) sur le FAIT du serveur, même si
                    // la coroutine lancante est morte entre-temps. Un
                    // SyncResult tardif (délai d'inactivité dépassé côté
                    // client puis serveur conclut quand même) conclut
                    // aussi : chronologie honnête, le dernier verdict gagne.
                    flusSync.send(EvenementSyncFlux.Terminal(AppResult.Success(evenement.versResultatDomaine())))
                    promesses.remove(evenement.id)?.complete(evenement)
                }

                is PartialSyncResult -> {
                    syncsEnVol.remove(evenement.id)
                    sync.value = EtatSyncTooling(enCours = false, projectDir = evenement.projectDir)
                    flusSync.send(EvenementSyncFlux.Terminal(AppResult.Success(evenement.versResultatDomaine())))
                    promesses.remove(evenement.id)?.complete(evenement)
                }

                else -> {
                    // Séparé du routage principal pour la complexité —
                    // inatteignable : le site d'appel filtre les trois types.
                    Unit
                }
            }
        }

        /**
         * Une étape de sync traverse (v3 ; v0.48.0 : dans le flux ORDONNÉ) :
         * elle part dans le canal du flux — l'état `enCours` reste porté par
         * [sync] (conflation légitime d'un ÉTAT), les ÉTAPES ne se mélangent
         * jamais.
         */
        private suspend fun pomperEtapeSync(evenement: SyncProgress) {
            // v4 (§3.1) puis v0.49.0 : le signe de vie est mesuré à la
            // RÉCEPTION (routeur) ; il ne reste ici que la traversée du
            // flux ordonné. v6 (prompt de suivi §2) : plus de champ
            // `sautee` — une phase qui n'a pas lieu n'est pas émise par le
            // serveur.
            flusSync.send(
                EvenementSyncFlux.Etape(
                    EtapeSyncTooling(
                        projectDir = evenement.projectDir,
                        etape = EtapeSync.valueOf(evenement.phase.name),
                        terminee = evenement.terminee,
                        dureeMs = evenement.dureeMs,
                        octetsRecus = evenement.octetsRecus,
                        octetsTotal = evenement.octetsTotal,
                        element = evenement.element,
                        compteur = evenement.compteur,
                        total = evenement.total,
                    ),
                ),
            )
        }

        /**
         * Une ligne de sortie de sync traverse (v0.48.0, ADR 0079) : elle
         * rejoint le flux ORDONNÉ — le VRAI flux de Gradle s'affiche dans
         * la console Sync, entre le départ et le terminal. Une ligne est un
         * signe de vie (le délai d'inactivité se réarme).
         */
        private suspend fun pomperSortieSync(evenement: SyncOutput) {
            // v0.49.0 : le signe de vie est mesuré à la RÉCEPTION (routeur).
            flusSync.send(
                EvenementSyncFlux.Ligne(
                    LigneSortieSync(
                        projectDir = evenement.projectDir,
                        flux = evenement.stream.versFluxDomaine(),
                        ligne = evenement.line,
                        horodatageMs = evenement.timestampMs,
                    ),
                ),
            )
        }

        /**
         * Un téléchargement du build traverse (v4, §6) : il part dans le
         * canal des téléchargements — les ProgressEvent TEXTUELS (statut
         * générique, sans détail) restent ignorés : rien à en faire
         * aujourd'hui, la progression utile est structurée.
         */
        private suspend fun pomperTelechargement(evenement: ProgressEvent) {
            val detail = evenement.telechargement ?: return
            telechargementsParBuild[evenement.buildId]?.send(
                TelechargementBuild(
                    buildId = evenement.buildId,
                    element = detail.element,
                    octetsRecus = detail.octetsRecus,
                    octetsTotal = detail.octetsTotal,
                    termine = detail.termine,
                    dureeMs = detail.dureeMs,
                    compteur = detail.compteur,
                ),
            )
        }

        /**
         * Une réponse d'ERREUR traverse (v0.48.0, ADR 0079) : un ErrorResponse
         * qui répond à une SYNC en vol est un TERMINAL — aucun SyncResult
         * n'arrivera derrière lui, le flux doit conclure pour que l'UI ne
         * reste pas « en cours » à jamais. La promesse suit son cours.
         */
        private fun pomperErreur(evenement: ErrorResponse) {
            if (syncsEnVol.remove(evenement.requestId)) {
                flusSync.trySend(
                    EvenementSyncFlux.Terminal(
                        AppResult.Failure(evenement.versErreurDomaine()),
                    ),
                )
            }
            promesses.remove(evenement.requestId)?.complete(evenement)
        }

        /** Une tâche démarre (v3) : elle part dans le canal du build. */
        private suspend fun pomperTacheDemarree(evenement: TaskStarted) {
            tachesParBuild[evenement.buildId]?.send(
                EtatTacheBuild(
                    buildId = evenement.buildId,
                    chemin = evenement.taskPath,
                    statut = StatutTache.EN_COURS,
                ),
            )
        }

        /**
         * Une tâche se termine (v3) : statut traduit (sautée ≠ réussie ≠
         * échouée — la console les distingue) et durée MESURÉE du côté du
         * serveur, la même source de vérité que [BuildFinished.durationMs].
         */
        private suspend fun pomperTacheTerminee(evenement: TaskFinished) {
            tachesParBuild[evenement.buildId]?.send(
                EtatTacheBuild(
                    buildId = evenement.buildId,
                    chemin = evenement.taskPath,
                    statut =
                        when {
                            evenement.skipped -> StatutTache.SAUTEE
                            evenement.succeeded -> StatutTache.REUSSIE
                            else -> StatutTache.ECHOUEE
                        },
                    dureeMs = evenement.durationMs,
                ),
            )
        }

        /**
         * Routage d'un événement À LA RÉCEPTION (v0.49.0, ADR 0080) : chemin
         * RAPIDE du lecteur — ne suspend JAMAIS (rien qu'un `trySend` vers
         * une voie non bornée et des écritures atomiques). Le pong est mis
         * à jour ICI, AVANT tout traitement : la santé ne peut plus être
         * retardée par une console ou un canal aval, quelle que soit la
         * charge (c'était le mécanisme du « orchestrateur muet » v0.48 :
         * une pompe unique suspendue sur un `send` plein).
         *
         * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod —
         * une branche par TYPE d'événement du protocole (le contrat complet
         * de l'orchestrateur), chacune déléguée d'une ligne : un aiguillage
         * plat, pas de la logique imbriquée — l'éclater déplacerait le
         * problème.
         */
        @Suppress("CyclomaticComplexMethod")
        private fun router(
            evenement: ToolingEvent,
            voieBuild: Channel<ToolingEvent>,
            voieSync: Channel<ToolingEvent>,
        ) {
            when (evenement) {
                // Chemin rapide DU health check (§5.4) : la réponse de santé
                // est la seule frame qui ne doit JAMAIS attendre.
                is PongMessage -> {
                    dernierPongMs.value = System.currentTimeMillis()
                }

                // v0.43.0 (mesure console lente) : la latence TRANSPORT se
                // mesure à la RÉCEPTION (ici), hors de tout retard aval —
                // l'écart restant se lit côté publication (GradleService).
                is BuildOutput -> {
                    latencesTransport
                        .computeIfAbsent(evenement.buildId) { AccumulateurLatence() }
                        .enregistrer(System.currentTimeMillis() - evenement.timestampMs)
                    deposer(voieBuild, profondeurBuild, "build", evenement)
                }

                is BuildStarted,
                is BuildFinished,
                is TaskStarted,
                is TaskFinished,
                is ProgressEvent,
                -> {
                    deposer(voieBuild, profondeurBuild, "build", evenement)
                }

                // v4 (§3.1) : tout événement de sync est un signe de vie,
                // mesuré à la RÉCEPTION — le délai d'inactivité ne peut
                // plus mourir derrière une console sync lente.
                is SyncStarted,
                is SyncResult,
                is PartialSyncResult,
                is SyncProgress,
                is SyncOutput,
                -> {
                    derniereActiviteSyncMs.set(System.currentTimeMillis())
                    deposer(voieSync, profondeurSync, "sync", evenement)
                }

                // v0.48.0 (ADR 0079) : un échec répondant à une sync en vol
                // CONCLUT le flux — il traverse la VOIE SYNC pour ne jamais
                // précéder les lignes qu'il conclut (l'ordre du câble EST
                // l'ordre de la console).
                is ErrorResponse -> {
                    deposer(voieSync, profondeurSync, "sync", evenement)
                }

                is HeapEvent -> {
                    tas.value = InstantaneTas(moUtilises = evenement.usedMb, moMax = evenement.maxMb)
                }

                is Diagnostic -> {
                    diagnosticsGlobal.value = listOf(evenement.versDiagnosticDomaine())
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

                // P1 (ADR 0095) : réponse asynchrone aux scripts de build —
                // la promesse est levée par l'appelant (P2), le routeur ne
                // fait que compléter.
                is BuildScriptsResult -> {
                    promesses.remove(evenement.id)?.complete(evenement)
                }

                // réponse de handshake déjà consommée
                is HelloResponse -> {
                    Unit
                }
            }
        }

        /**
         * Dépose un événement dans sa voie ordonnée et surveille la
         * profondeur : une voie qui croît signale un consommateur aval en
         * retard (console lente) — le retard se NOMME en se formant, le
         * signalement est borné en débit.
         */
        private fun deposer(
            voie: Channel<ToolingEvent>,
            profondeur: java.util.concurrent.atomic.AtomicLong,
            nom: String,
            evenement: ToolingEvent,
        ) {
            voie.trySend(evenement)
            val valeur = profondeur.incrementAndGet()
            if (valeur >= SEUIL_ALERTE_VOIE) {
                val maintenant = System.currentTimeMillis()
                val dernier = dernierAvertissementVoieMs.get()
                if (maintenant - dernier >= INTERVALLE_ALERTE_VOIE_MS &&
                    dernierAvertissementVoieMs.compareAndSet(dernier, maintenant)
                ) {
                    journal.w(TAG_POMPE) {
                        "voie $nom à $valeur événement(s) en attente — la console aval est en retard"
                    }
                }
            }
        }

        /**
         * Consommateur de la voie BUILD (v0.49.0) : les envois suspendants
         * de la famille build, DANS l'ordre d'arrivée — l'ordre relatif
         * lignes/état/clôture de canal est celui du câble.
         */
        private suspend fun pomperBuild(evenement: ToolingEvent) {
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

                is ProgressEvent -> {
                    pomperTelechargement(evenement)
                    pomperStatutBuild(evenement)
                }

                is TaskStarted -> {
                    pomperTacheDemarree(evenement)
                }

                is TaskFinished -> {
                    pomperTacheTerminee(evenement)
                }

                else -> {
                    // Inatteignable : le routeur a déjà filtré la famille.
                    Unit
                }
            }
        }

        /**
         * Consommateur de la voie SYNC (v0.49.0) : la famille sync ET les
         * ErrorResponse qui la concluent, DANS l'ordre d'arrivée — le
         * terminal ne peut pas précéder les lignes qu'il conclut (ADR
         * 0079), la promesse se réveille APRÈS le terminal (même ordre que
         * le câble).
         */
        private suspend fun pomperSyncFamille(evenement: ToolingEvent) {
            when (evenement) {
                is SyncStarted,
                is SyncResult,
                is PartialSyncResult,
                -> {
                    pomperSync(evenement)
                }

                is SyncProgress -> {
                    pomperEtapeSync(evenement)
                }

                is SyncOutput -> {
                    pomperSortieSync(evenement)
                }

                is ErrorResponse -> {
                    pomperErreur(evenement)
                }

                else -> {
                    // Inatteignable : le routeur a déjà filtré la famille.
                    Unit
                }
            }
        }

        // ------------------------------------------------------------------
        // Diffusion (§5.2/§5.3).
        // ------------------------------------------------------------------

        override fun observeBuildOutput(buildId: String): Flow<LigneSortieBuild> = sortie(buildId).receiveAsFlow()

        override fun observeTachesBuild(buildId: String): Flow<EtatTacheBuild> = taches(buildId).receiveAsFlow()

        override fun observeTelechargementsBuild(buildId: String): Flow<TelechargementBuild> =
            telechargements(buildId).receiveAsFlow()

        override fun observeBuildState(buildId: String): Flow<EtatBuild> = etat(buildId).asStateFlow()

        override fun observeHeap(): Flow<InstantaneTas> = tas.asStateFlow()

        override fun observeConnectionState(): Flow<EtatConnexion> = connexion.asStateFlow()

        override fun observeSyncState(): Flow<EtatSyncTooling> = sync.asStateFlow()

        override fun observeFluxSync(): Flow<EvenementSyncFlux> = flusSync.receiveAsFlow()

        override fun observeDiagnostics(projectDir: File): Flow<List<DiagnosticBuild>> = diagnosticsGlobal.asStateFlow()

        // ------------------------------------------------------------------
        // Opérations (§5.3).
        // ------------------------------------------------------------------

        override suspend fun synchroniser(
            projectDir: File,
            arguments: List<String>,
        ): AppResult<ResultatSynchronisation> {
            val reponse =
                echangerAvecInactivite(
                    SyncRequest(
                        id = nouvelIdentifiant(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = projectDir.canonicalPath,
                        arguments = arguments,
                    ),
                ) ?: return echecConnexion()
            return when (reponse) {
                // v0.48.0 (ADR 0079) : la MÊME traduction que le terminal du
                // flux ordonné (versResultatDomaine) — le résultat attendu
                // par l'appelant et celui publié à l'UI par la vidange ne
                // peuvent pas diverger.
                is SyncResult -> {
                    AppResult.Success(reponse.versResultatDomaine())
                }

                is PartialSyncResult -> {
                    AppResult.Success(reponse.versResultatDomaine())
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

        override suspend fun classpath(
            projectDir: File,
            arguments: List<String>,
        ): AppResult<ClasspathProjet> {
            val reponse =
                echanger(
                    ClasspathRequest(
                        id = nouvelIdentifiant(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        projectDir = projectDir.canonicalPath,
                        arguments = arguments,
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
            arguments: List<String>,
        ): String {
            val buildId = nouvelIdentifiant()
            // Canal et état créés AVANT l'envoi : les événements qui arrivent
            // PENDANT le lancement (l'orchestrateur émet dès BuildStarted)
            // se tamponnent au lieu d'être perdus — la souscription après le
            // lancement ne manque rien (§5.2). Même tamponnement pour les
            // TÂCHES (v3) et les TÉLÉCHARGEMENTS (v4 §6) : les événements
            // précoces attendent le collecteur.
            sortie(buildId)
            taches(buildId)
            telechargements(buildId)
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
                        arguments = arguments,
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

        override fun envoyerEntree(
            buildId: String,
            texte: String,
        ) {
            val sessionCourante = session ?: return
            // v0.41.1 : fire-and-forget — envoyer l'entrée stdin au build
            // en cours (readln, Scanner(System.in)). Le serveur écrit sur
            // le PipedOutputStream branché au process Gradle.
            porteePompe?.launch {
                runCatching {
                    sessionCourante.envoyer(
                        BuildInput(
                            id = nouvelIdentifiant(),
                            protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                            buildId = buildId,
                            texte = texte,
                        ),
                    )
                }
            }
        }

        // ------------------------------------------------------------------
        // Intérieur.
        // ------------------------------------------------------------------

        /**
         * Échange À DÉLAI D'INACTIVITÉ (v4, §3.1) : contrairement à
         * [echanger] (délai TOTAL), la fenêtre se RÉARMÉ à chaque signe de
         * vie de la sync ([derniereActiviteSyncMs] — étapes, progressions,
         * téléchargements d'octets) — une sync qui progresse reste vivante
         * aussi longtemps qu'elle avance, seule la SILENCE la tue (un
         * réseau mobile lent n'est plus un échec de 5 minutes).
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — clauses de
         * garde (absence de session, échec d'envoi) retournant chacune une
         * valeur typée ; SwallowedException — l'échec d'envoi est ATTENDU
         * et traduit en échec de connexion par l'appelant, le délai devient
         * [ErrorResponse] typé (l'exception sert de message), jamais avalés
         * silencieusement.
         */
        @Suppress("ReturnCount", "SwallowedException")
        private suspend fun echangerAvecInactivite(requete: ToolingRequest): ToolingEvent? {
            val sessionCourante = session ?: return null
            // v0.48.0 : la requête de sync est EN VOL — un ErrorResponse, un
            // SyncResult tardif ou une rupture saura la conclure (terminal
            // du flux ordonné, ADR 0079).
            if (requete is SyncRequest) syncsEnVol.add(requete.id)
            val promesse = CompletableDeferred<ToolingEvent>()
            promesses[requete.id] = promesse
            try {
                sessionCourante.envoyer(requete)
            } catch (perdue: java.io.IOException) {
                promesses.remove(requete.id)
                if (requete is SyncRequest) syncsEnVol.remove(requete.id)
                return null
            }
            while (true) {
                val repere = derniereActiviteSyncMs.get()
                try {
                    return withTimeout(delaiInactiviteSyncMs) { promesse.await() }
                } catch (delaiDepasse: TimeoutCancellationException) {
                    if (derniereActiviteSyncMs.get() != repere) {
                        // Un événement est arrivé PENDANT la fenêtre : la
                        // progression reprend son droit — nouvelle fenêtre.
                        continue
                    }
                    promesses.remove(requete.id)
                    return ErrorResponse(
                        id = requete.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        requestId = requete.id,
                        code = ErrorCode.TIMEOUT,
                        message =
                            "aucun événement de sync depuis $delaiInactiviteSyncMs ms " +
                                "(${delaiDepasse.message ?: "timeout"})",
                    )
                }
            }
        }

        /**
         * Envoie une requête et attend SA réponse (promesse + délai TOTAL).
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
                    tachesParBuild[buildId]?.close()
                    telechargementsParBuild[buildId]?.close()
                }
        }

        private fun sortie(buildId: String): Channel<LigneSortieBuild> =
            sorties.computeIfAbsent(buildId) { Channel(TAILLE_TAMPON_SORTIE) }

        /** Canal des tâches du build (v3) — borné, envoi suspendant. */
        private fun taches(buildId: String): Channel<EtatTacheBuild> =
            tachesParBuild.computeIfAbsent(buildId) { Channel(TAILLE_TAMPON_SORTIE) }

        /** Canal des téléchargements du build (v4) — borné, envoi suspendant. */
        private fun telechargements(buildId: String): Channel<TelechargementBuild> =
            telechargementsParBuild.computeIfAbsent(buildId) { Channel(TAILLE_TAMPON_SORTIE) }

        private fun etat(buildId: String): MutableStateFlow<EtatBuild> =
            etats.computeIfAbsent(buildId) {
                MutableStateFlow(EtatBuild(buildId = buildId, statut = StatutBuild.EN_COURS))
            }

        /** Une ligne de sortie arrive : elle part dans le canal du build. */
        private suspend fun pomperSortie(evenement: BuildOutput) {
            // v0.49.0 : la latence TRANSPORT est enregistrée à la réception
            // par le routeur — le retard restant se mesure côté publication.
            sorties[evenement.buildId]?.send(
                LigneSortieBuild(
                    buildId = evenement.buildId,
                    flux = evenement.stream.versFluxDomaine(),
                    ligne = evenement.line,
                    horodatageMs = evenement.timestampMs,
                ),
            )
        }

        /**
         * Un statut TEXTUEL du build traverse (v0.45.1 — affichage
         * immédiat, parité Android Studio) : le [ProgressEvent] SANS détail
         * de téléchargement (« Configuration :app… », « Exécution des
         * tâches : … », « connexion au daemon Gradle… ») devient une LIGNE
         * du canal de sortie du build — la plomberie existante (pompe
         * process-wide puis zone texte, append par trame) l'affiche AU
         * MOMENT où il arrive. Jusqu'ici ces événements étaient publiés par
         * le serveur puis JETÉS ici (`?: return` sur le détail) : la console
         * restait aveugle pendant toute la fenêtre pré-tâches (connexion du
         * daemon, configuration) — précisément là où s'accumulaient les
         * minutes de silence constatées.
         *
         * L'horodatage est pris ICI (réception) : le protocole ne porte pas
         * de repère d'émission sur ce type — la latence de PUBLICATION de
         * ces lignes mesure donc la seule moitié cliente, à lire comme un
         * plancher.
         */
        private suspend fun pomperStatutBuild(evenement: ProgressEvent) {
            if (evenement.telechargement != null) return
            sorties[evenement.buildId]?.send(
                LigneSortieBuild(
                    buildId = evenement.buildId,
                    flux = FluxSortieBuild.STDOUT,
                    ligne = evenement.message,
                    horodatageMs = System.currentTimeMillis(),
                ),
            )
        }

        /** Le build démarre : son état passe à EN_COURS. */
        private fun pomperDemarrage(evenement: BuildStarted) {
            etat(evenement.buildId).value =
                EtatBuild(buildId = evenement.buildId, statut = StatutBuild.EN_COURS)
        }

        /**
         * Le build se termine : état final, puis les canaux se FERMENT sans
         * être retirés — un collecteur vivant draine ce qui reste puis
         * complète : aucune perte, aucun collecteur laissé en suspens. La
         * mémoire est bornée par la durée de la SESSION :
         * [fermerSession] nettoie tout.
         *
         * v0.39.1 (correctif n°4) : la synthèse d'Android Studio
         * (« N actionable tasks: M executed[, K up-to-date] ») extraite
         * côté serveur de la dernière ligne stdout de Gradle voyage dans
         * [BuildFinished] — on la propage à [EtatBuild] pour que la
         * console l'affiche sous le verdict, comme Android Studio.
         *
         * v0.39.1 (correctif n°5) : un build ANNULÉ (`cancelled = true`)
         * est distingué d'un échec réel — `StatutBuild.ANNULE` au lieu de
         * `ECHOUE`. La pastille du BottomSheet rend l'annulation en mode
         * atténué, pas en rouge d'échec.
         */
        private fun pomperFin(evenement: BuildFinished) {
            val statutFinal =
                when {
                    evenement.succeeded -> StatutBuild.REUSSI
                    evenement.cancelled -> StatutBuild.ANNULE
                    else -> StatutBuild.ECHOUE
                }
            // v0.43.0 (mesure console lente) : résumé de latence TRANSPORT
            // au terme du build — émission serveur → réception client. À
            // lire avec le résumé de PUBLICATION de GradleService : la
            // difference entre les deux maxima mesure la moitié cliente
            // (canaux, pompe, zone texte).
            latencesTransport.remove(evenement.buildId)?.let { latence ->
                journal.i(TAG_LATENCE) {
                    "sorties du build ${evenement.buildId} reçues : ${latence.description()} " +
                        "(émission orchestrateur → réception client, build ${evenement.durationMs} ms)"
                }
            }
            etat(evenement.buildId).value =
                EtatBuild(
                    buildId = evenement.buildId,
                    statut = statutFinal,
                    dureeMs = evenement.durationMs,
                    messageEchec = evenement.failureMessage,
                    tachesActionnables = evenement.actionableTasks,
                    tachesExecutees = evenement.executedTasks,
                    tachesAJour = evenement.upToDateTasks,
                )
            sorties[evenement.buildId]?.close()
            tachesParBuild[evenement.buildId]?.close()
            telechargementsParBuild[evenement.buildId]?.close()
        }

        private fun nouvelIdentifiant(): String = UUID.randomUUID().toString()

        // v0.45.1 : les traductions protocole → domaine (versFluxDomaine,
        // versDiagnosticDomaine, versClasspathDomaine, versErreurDomaine)
        // vivent désormais dans ConversionsDomaine.kt — fonctions pures
        // extraites quand la classe a dépassé la limite de complexité.

        private companion object {
            /**
             * Voie DÉDIÉE de la pompe (v0.49.0, ADR 0080) : trois fils au
             * plus — lecteur + consommateur build + consommateur sync. Une
             * voie DÉDIÉE de `Dispatchers.IO` (et non le dispatcheur par
             * DÉFAUT partagé) : la saturation de celui-ci par le travail
             * CPU (préparation du classpath LSP, surlignage) ne peut plus
             * retarder la lecture du socket — les coroutines de la pompe
             * ne partagent plus leur ordonnanceur avec ce travail-là.
             */
            private val DISPATCH_POMPE = Dispatchers.IO.limitedParallelism(3)

            /**
             * Tampon de sortie par build (§5.2 : 4096, envoi suspendant —
             * jamais conflaté, jamais perdant).
             */
            const val TAILLE_TAMPON_SORTIE = 4096

            /**
             * Tampon des étapes de sync (v3) : une sync annonce six
             * étapes au plus (départ + fin par phase) — 256 couvre des
             * dizaines de sync non collectées, et l'envoi suspendant
             * borne la mémoire avant ça (contre-pression, jamais perdu).
             */
            const val TAILLE_TAMPON_SYNC = 256

            /** Délais client des requêtes-réponses (§7.5). */
            const val DELAI_TACHES_MS: Long = 30_000L

            /**
             * Délai d'INACTIVITÉ de la sync (v4 §3.1) : la fenêtre se réarme
             * à chaque événement — 5 minutes de TOTAL laissaient mourir une
             * sync qui progressait (premier lancement, réseau mobile).
             */
            const val DELAI_INACTIVITE_SYNC_MS: Long = 90_000L

            /** Délai client du classpath LSP (ADR 0058, aligné serveur). */
            const val DELAI_CLASSPATH_MS: Long = 5 * 60_000L

            /** Étiquette des résumés de latence (mesure console, v0.43.0). */
            const val TAG_LATENCE = "ConsoleLatence"

            /** Étiquette de la pompe cliente (v0.49.0, ADR 0080). */
            const val TAG_POMPE = "ToolingClient"

            /** Profondeur d'une voie au-delà de laquelle elle est signalée. */
            const val SEUIL_ALERTE_VOIE = 2_048L

            /**
             * Fenêtre bornée de drainage des voies à la fin du flux
             * (v0.50.0) : les lignes déjà routées finissent leur chemin avant
             * la conclusion des builds — une console aval morte ne retarde
             * jamais la conclusion au-delà.
             */
            const val FENETRE_DRAINAGE_VOIES_MS: Long = 2_000L

            /** Bornage du débit des avertissements de voie (5 s). */
            const val INTERVALLE_ALERTE_VOIE_MS = 5_000L
        }
    }
