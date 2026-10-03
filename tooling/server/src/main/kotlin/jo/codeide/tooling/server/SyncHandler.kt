package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.StreamKind
import jo.codeide.tooling.protocol.SyncOutput
import jo.codeide.tooling.protocol.SyncPhase
import jo.codeide.tooling.protocol.SyncRequest
import jo.codeide.tooling.protocol.SyncResult
import jo.codeide.tooling.protocol.SyncStarted
import jo.codeide.tooling.protocol.TaskInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gradle.tooling.events.OperationType
import java.io.File

/**
 * Synchronisation de projet (v4 — §3.1 du prompt « tooling professionnel ») :
 * les phases annoncées sont RÉELLES et le déroulé ne ment plus.
 *
 * - **OUTILS** : vérifications locales (dossier, wrapper, distribution en
 *   cache) AVANT toute requête ;
 * - **DISTRIBUTION** : le téléchargement/décompression de la distribution
 *   a SA phase — mais elle ne se DÉROULE que si la distribution MANQUE
 *   (v5, aperçu : déjà en cache → phase SAUTÉE, la console rend « en
 *   cache » et n'affiche un téléchargement que s'il a lieu) ; les octets
 *   reçus viennent du sondeur des fichiers `.part` (la Tooling API ne
 *   donne AUCUN octet pour la distribution — vérifié sur le JAR 9.7.1) ;
 * - **DAEMON** : démarrage du daemon, conclu au premier événement de
 *   configuration (ou à la fin) ;
 * - **CONFIGURATION** : événements `PROJECT_CONFIGURATION` de la Tooling
 *   API, un par projet (compteur n) ;
 * - **MODELE_TACHES puis MODELE_IDE** : l'action UNIQUE résout les DEUX
 *   modèles dans UNE requête (l'ancienne double suite de `model().get()`
 *   configurait le build deux fois) — les transitions streament par
 *   `BuildController.send` vers le `StreamedValueListener` ;
 * - **DEPENDANCES** : événements `FILE_DOWNLOAD` (artefact, octets reçus
 *   cumulés, compteur n) — n'arrive que si des téléchargements ont LIEU ;
 * - **CLASSPATHS** : les classpaths LSP extraits de `IdeaProject`, publiés
 *   AVANT [SyncResult] — « Synchronisé » ne s'affiche qu'après.
 *
 * v0.48.0 (ADR 0079) : la stdout/stderr de l'action est CAPTURÉE ligne à
 * ligne ([StreamingFluxSync] → [SyncOutput]) — la console Sync montre le
 * VRAI flux de Gradle (avertissements de configuration, `println` de
 * build script) comme la fenêtre Sync d'Android Studio, et les statuts
 * textuels de la fenêtre daemon (« Starting Gradle Daemon ») y sont
 * republiés par [EcouteurStatutLegacy].
 *
 * Les arguments réglés (`--offline`, arguments libres) s'appliquent à la
 * requête (v4) : la sync cesse de les ignorer. Le résultat de l'action
 * alimente le [CacheSync] (listage et classpath répondent sans re-résoudre).
 *
 * Écoute des events : [EcouteurProgressionCommun] (FILE_DOWNLOAD +
 * PROJECT_CONFIGURATION, débit borné) et l'écouteur HISTORIQUE de statut
 * (descriptions textuelles de la distribution/du daemon — l'unique
 * signal TAPI pendant la résolution de la distribution).
 */
internal class SyncHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
    private val cache: CacheSync = CacheSync(),
) {
    /**
     * Résout les modèles du projet et publie [SyncStarted], les phases
     * réelles puis [SyncResult] ou [PartialSyncResult].
     */
    suspend fun synchroniser(requete: SyncRequest) {
        val debut = System.currentTimeMillis()
        val dossier = File(requete.projectDir)
        val phases = ConteurPhasesSync(requete.projectDir, bus)

        // Départ annoncé AVANT toute résolution (étape 32, ADR 0057) :
        // symétrique du BuildStarted des builds — l'app rend son état sur
        // un événement DU serveur, pas sur la présomption de son propre geste.
        bus.publier(
            SyncStarted(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
            ),
        )

        // ---- OUTILS : vérifications locales (rapides, sans requête) -----
        val urlWrapper =
            mesurerLocale(phases) {
                if (!dossier.isDirectory) {
                    error("répertoire introuvable : ${requete.projectDir}")
                }
                EtatsDistribution.lireUrlWrapper(dossier)
            }

        // ---- Connexion (sans phase : connect() ne télécharge rien —
        // v4 corrige le mensonge de l'ancienne phase CONNEXION). ----------
        val connexion =
            try {
                withContext(Dispatchers.IO) { pool.connexion(dossier) }
            } catch (t: Throwable) {
                Journal.warn("connexion Gradle impossible : ${t.message}")
                phases.conclureTout()
                publierEchecSec(requete, debut, "connexion Gradle impossible : ${t.message}")
                return
            }

        // ---- DISTRIBUTION (v6, prompt de suivi §2 : une phase qui n'a
        // pas lieu n'est plus émise du tout — fini le « En cache ») :
        // si la distribution est déjà en cache, on ne l'ouvre PAS et on
        // ne la conclut PAS — le client ne l'affiche pas du tout. Si elle
        // MANQUE, on l'ouvre et le sondeur la conclut quand le téléchargement
        // se termine. -----------------------------------------------------
        if (!EtatsDistribution.estInstallee(urlWrapper)) {
            phases.ouvrir(SyncPhase.DISTRIBUTION, element = urlWrapper?.substringAfterLast('/'))
        }

        // ---- DAEMON : ouvert avant l'action, conclu au premier événement
        // de configuration de projet (ou à la fin de l'action). -----------
        phases.ouvrir(SyncPhase.DAEMON)

        // ---- L'action unique (modèles + classpaths + options). ----------
        val resultat =
            try {
                coroutineScope {
                    val sondeur = lancerSondeurDistribution(phases, urlWrapper)
                    try {
                        executerAction(requete, phases, connexion)
                    } finally {
                        sondeur.cancel()
                    }
                }
            } catch (t: Throwable) {
                Journal.warn("action de sync échouée : ${t.message}")
                phases.conclureTout()
                publierEchecSec(requete, debut, "synchronisation échouée : ${t.message}")
                return
            }

        // ---- Conclusion : classpaths puis résultat (§3.2 — « Synchronisé »
        // n'apparaît qu'après la dernière phase). -------------------------
        conclureAvecSucces(requete, phases, resultat, debut)
    }

    /** Sortie d'échec sec — les phases pendantes sont closes par l'appelant. */
    private fun publierEchecSec(
        requete: SyncRequest,
        debut: Long,
        message: String,
    ) {
        bus.publier(
            SyncResult(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                succeeded = false,
                durationMs = System.currentTimeMillis() - debut,
                failureMessage = message,
            ),
        )
    }

    /** Conclusion : CLASSPATHS avant le résultat, cache déposé, phases
     *  pendantes closes — « Synchronisé » n'apparaît qu'après la dernière
     *  phase (§3.2).
     *
     *  v0.47.0 : le [SyncResult] porte les tâches résolues PAR l'action
     *  (`taches`) — le client arme le bouton Tâches SUR LE RÉSULTAT,
     *  sans second aller-retour de listage (retour utilisateur : « une
     *  fois la sync terminée, le bouton devrait être immédiatement
     *  activé » — l'aller-retour `TasksRequest` qui suivait laissait le
     *  bouton inerte le temps d'un IPC même servi par le cache). */
    private fun conclureAvecSucces(
        requete: SyncRequest,
        phases: ConteurPhasesSync,
        resultat: ResultatModelesSync,
        debut: Long,
    ) {
        phases.ouvrir(SyncPhase.CLASSPATHS)
        phases.conclure(SyncPhase.CLASSPATHS, compteur = resultat.modules.size)

        // Le cache alimente listage et classpath (v4) : une seule
        // résolution, tout le monde en profite.
        cache.deposer(requete.projectDir, resultat.taches, resultat.modules)

        phases.conclureTout()
        bus.publier(
            SyncResult(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                succeeded = true,
                durationMs = System.currentTimeMillis() - debut,
                taches =
                    resultat.taches.map { dto ->
                        TaskInfo(path = dto.path, group = dto.group, displayName = dto.displayName)
                    },
            ),
        )
    }

    /**
     * Exécute l'action unique : arguments réglés, écouteurs communs
     * (téléchargements + configuration), marqueurs de phases streamés,
     * exécution bloquante sur E/S sous le délai du dispatcher.
     */
    private suspend fun executerAction(
        requete: SyncRequest,
        phases: ConteurPhasesSync,
        connexion: org.gradle.tooling.ProjectConnection,
    ): ResultatModelesSync {
        val daemonConclu =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val ecouteur =
            EcouteurProgressionCommun(
                surTelechargement = { detail -> phases.surTelechargement(detail) },
                surConfiguration = { element, terminee, compteur ->
                    // Le premier événement de configuration prouve que le
                    // daemon tourne et configure : DAEMON se conclut là.
                    if (daemonConclu.compareAndSet(false, true)) {
                        phases.conclure(SyncPhase.DAEMON)
                    }
                    phases.surConfiguration(element, terminee, compteur)
                },
            )
        val ecouteurStatut = EcouteurStatutLegacy(requete.projectDir, phases, bus)

        return withContext(Dispatchers.IO) {
            val executer =
                connexion
                    .action(ActionSyncModeles())
                    .withArguments(requete.arguments + CONSOLE_TEXTE)
                    .addProgressListener(
                        ecouteur,
                        OperationType.FILE_DOWNLOAD,
                        OperationType.PROJECT_CONFIGURATION,
                    ).addProgressListener(ecouteurStatut)
            // v0.48.0 (ADR 0079) : la capture stdout/stderr de la sync
            // REVIENT — mais sur SON message. La capture v0.41.1 publiait
            // des BuildOutput avec l'identifiant de REQUÊTE SYNC comme
            // buildId — or le client n'ouvrait un canal de sortie QUE pour
            // les identifiants de BUILD (GradleApiImpl.sorties) : chaque
            // ligne était publiée sur le bus puis JETÉE à la réception
            // (publication morte — c'est CE constat qui avait motivé la
            // suppression v0.45.1). Le bon correctif n'était pas de couper
            // la capture mais de lui donner son canal : [StreamingFluxSync]
            // publie des [SyncOutput] (dossier du projet, pas de buildId)
            // que le client route vers la console Sync. La console de sync
            // cesse d'être muette : le flux de Gradle s'y lit, comme la
            // fenêtre Sync d'Android Studio — retour terrain v0.47.0 :
            // « lors d'un Sync la console manque les outputs nécessaires,
            // la plupart est affiché dans le header du bottomsheet ».
            // Les marqueurs de phases streamés (vérifié sur le JAR 9.7.1 :
            // `setStreamedValueListener` retourne void, il ne s'enchaîne
            // PAS — posé avant `run`, les valeurs arrivent pendant).
            executer.setStreamedValueListener { valeur ->
                when (valeur as? MarqueurPhaseModele) {
                    MarqueurPhaseModele.MODELE_TACHES -> {
                        phases.ouvrir(SyncPhase.MODELE_TACHES)
                    }

                    MarqueurPhaseModele.MODELE_IDE -> {
                        phases.conclure(SyncPhase.MODELE_TACHES)
                        phases.ouvrir(SyncPhase.MODELE_IDE)
                    }

                    null -> {
                        Unit
                    }
                }
            }
            // v0.48.0 : le VRAI flux de Gradle, stdout ET stderr — les
            // avertissements de configuration et les `println` de build
            // script s'affichent dans la console Sync (stderr en rouge au
            // rendu, même discipline que le canal Build).
            executer.setStandardOutput(StreamingFluxSync(requete.projectDir, StreamKind.STDOUT, bus))
            executer.setStandardError(StreamingFluxSync(requete.projectDir, StreamKind.STDERR, bus))
            executer.run()
        }
    }

    /**
     * Sondeur de la distribution pendant l'action (500 ms) : octets reçus
     * (fichiers `.part`), conclusion dès que le marqueur `.ok` apparaît.
     * Inactif si la distribution était déjà installée (ou sans wrapper —
     * la Tooling API résout sa propre distribution, rien à sonder).
     */
    private fun CoroutineScope.lancerSondeurDistribution(
        phases: ConteurPhasesSync,
        urlWrapper: String?,
    ): Job =
        launch {
            if (urlWrapper == null || EtatsDistribution.estInstallee(urlWrapper)) return@launch
            while (isActive) {
                if (EtatsDistribution.estInstallee(urlWrapper)) {
                    // Marqueur `.ok` : la distribution est résolue — le
                    // daemon démarre derrière, la phase se conclut.
                    phases.conclure(SyncPhase.DISTRIBUTION)
                    return@launch
                }
                val octets = EtatsDistribution.octetsPartiels()
                if (octets > 0) {
                    phases.progression(
                        SyncPhase.DISTRIBUTION,
                        element = urlWrapper.substringAfterLast('/'),
                        octetsRecus = octets,
                    )
                }
                delay(PERIODE_SONDAGE_DISTRIBUTION_MS)
            }
        }

    /** Exécute [bloc] dans la phase OUTILS (départ + conclusion, rapide). */
    private fun <T> mesurerLocale(
        phases: ConteurPhasesSync,
        bloc: () -> T,
    ): T {
        phases.ouvrir(SyncPhase.OUTILS)
        try {
            return bloc()
        } finally {
            phases.conclure(SyncPhase.OUTILS)
        }
    }

    private companion object {
        /** Période du sondeur de distribution (ms). */
        const val PERIODE_SONDAGE_DISTRIBUTION_MS = 500L

        /** Sortie Gradle en texte brut — même discipline que le build (v3). */
        const val CONSOLE_TEXTE = "--console=plain"
    }
}

/**
 * Écouteur HISTORIQUE de la Tooling API (v4, §3.1 : « l'écouteur
 * historique pour le téléchargement de la distribution ») : les statuts
 * textuels (`statusChanged`/`ProgressEvent.getDescription`) sont le SEUL
 * signal pendant la résolution de la distribution (« Downloading… »,
 * « Unzipping… ») — ils nourrissent l'élément courant de la phase
 * DISTRIBUTION, sans jamais traverser le bus en flot continu.
 *
 * v0.45.2 (fenêtre daemon visible) : les statuts parlant du DAEMON
 * (« Starting Gradle Daemon », « Connecting to Gradle Daemon » — le
 * spawn peut prendre des minutes sur téléphone) alimentent la phase
 * DAEMON de la vue Sync plutôt que la phase DISTRIBUTION : l'étape
 * « Daemon » montre CE qu'elle attend, la distribution reste ce
 * qu'elle était.
 *
 * v0.48.0 (ADR 0079) : chaque statut dont le TEXTE CHANGE est aussi
 * republié comme ligne de la console Sync ([SyncOutput]) — retour
 * terrain v0.47.0 : « la plupart est affiché dans le header du
 * bottomsheet ». Ces descriptions sont le seul signal vivant pendant
 * la fenêtre daemon (des minutes sur un téléphone froid) : elles
 * appartiennent à la console, comme « Starting Gradle Daemon » dans la
 * fenêtre Build d'Android Studio. La déduplication par changement de
 * texte (plus le rabotage 5/s) borne le débit : un statut RÉPÉTÉ
 * n'écrit rien.
 */
internal class EcouteurStatutLegacy(
    private val projectDir: String,
    private val phases: ConteurPhasesSync,
    private val bus: EventBus,
) : org.gradle.tooling.ProgressListener {
    private var dernierStatutMs = 0L

    /** Dernier texte publié en console (déduplication par changement). */
    @Volatile
    private var dernierTexteConsole: String? = null

    override fun statusChanged(evenement: org.gradle.tooling.ProgressEvent) {
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierStatutMs < PERIODE_MINIMALE_MS) return
        dernierStatutMs = maintenant
        val description = evenement.description
        if (description.isBlank()) return
        val phase =
            if (description.contains("daemon", ignoreCase = true)) {
                SyncPhase.DAEMON
            } else {
                SyncPhase.DISTRIBUTION
            }
        phases.progression(phase, element = description)
        publierEnConsoleSiNouveau(description)
    }

    /** Republie le statut en console Sync quand son texte CHANGE — la
     *  console est l'historique, l'en-tête du panneau le présent. */
    private fun publierEnConsoleSiNouveau(description: String) {
        if (dernierTexteConsole == description) return
        dernierTexteConsole = description
        bus.publier(
            SyncOutput(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projectDir,
                stream = StreamKind.STDOUT,
                line = description,
                timestampMs = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        /** Borne de débit des statuts textuels (5/s — exigence §3.1). */
        const val PERIODE_MINIMALE_MS = 200L
    }
}
