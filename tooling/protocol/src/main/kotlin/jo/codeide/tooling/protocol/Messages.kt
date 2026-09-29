package jo.codeide.tooling.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Catégorie d'un flux de sortie d'un build (§3.2, `BuildOutput`).
 */
@Serializable
public enum class StreamKind {
    /** Sortie standard (`stdout`). */
    @SerialName("stdout")
    STDOUT,

    /** Sortie d'erreur (`stderr`). */
    @SerialName("stderr")
    STDERR,
}

/**
 * Gravité d'un diagnostic remonté par le tooling (§3.2, `Diagnostic`).
 */
@Serializable
public enum class DiagnosticSeverity {
    /** Erreur bloquante (échec de compilation, tâche en échec). */
    @SerialName("error")
    ERROR,

    /** Avertissement. */
    @SerialName("warning")
    WARNING,

    /** Information. */
    @SerialName("info")
    INFO,
}

/**
 * Code machine-lisible d'une erreur du protocole (§3.2 : « jamais une
 * chaîne libre sans code machine-lisible ») — l'UI traduit, elle
 * n'interprète jamais le texte brut.
 */
@Serializable
public enum class ErrorCode {
    /** Versions de protocole incompatibles (message clair, jamais indéfini). */
    @SerialName("protocol_version_mismatch")
    PROTOCOL_VERSION_MISMATCH,

    /** Secret de handshake invalide (§4.4). */
    @SerialName("handshake_failed")
    HANDSHAKE_FAILED,

    /** Type de message inconnu du décodeur. */
    @SerialName("unknown_request")
    UNKNOWN_REQUEST,

    /** Frame annoncée trop grande (garde DoS, §3.1). */
    @SerialName("frame_too_large")
    FRAME_TOO_LARGE,

    /** Frame tronquée ou corrompue. */
    @SerialName("malformed_frame")
    MALFORMED_FRAME,

    /** Délai dépassé (build 30 min, sync 5 min, tâches 30 s — §7.5). */
    @SerialName("timeout")
    TIMEOUT,

    /** Connexion perdue avec le pair. */
    @SerialName("connection_lost")
    CONNECTION_LOST,

    /** Le lancement du build a échoué avant de produire un événement. */
    @SerialName("build_launch_failed")
    BUILD_LAUNCH_FAILED,

    /** Erreur interne non classée du serveur. */
    @SerialName("internal_error")
    INTERNAL_ERROR,
}

/**
 * Toute unité échangée sur le socket (§3.2) — requête (app → process
 * JVM) ou événement (process JVM → app).
 */
@Serializable
public sealed interface ProtocolMessage {
    /** Identifiant de corrélation (réponse et événements d'une requête). */
    public val id: String

    /** Version de protocole de l'émetteur (négociée au handshake). */
    public val protocolVersion: Int
}

/** Requêtes émises par l'app Android vers le process JVM (§3.2). */
@Serializable
public sealed interface ToolingRequest : ProtocolMessage

/** Événements émis par le process JVM vers l'app Android (§3.2). */
@Serializable
public sealed interface ToolingEvent : ProtocolMessage

// ---------------------------------------------------------------------------
// Handshake (§4.4 : le secret s'échange ici, jamais écrit sur disque).
// ---------------------------------------------------------------------------

/** Première requête du process JVM : version + secret de handshake. */
@Serializable
@SerialName("hello_request")
public data class HelloRequest(
    override val id: String,
    override val protocolVersion: Int,
    public val clientVersion: String,
    public val handshakeSecret: String,
) : ToolingRequest

/** Réponse de l'orchestrateur : versions et fonctionnalités supportées. */
@Serializable
@SerialName("hello_response")
public data class HelloResponse(
    override val id: String,
    override val protocolVersion: Int,
    public val serverVersion: String,
    public val gradleToolingApiVersion: String,
    public val supportedFeatures: Set<String>,
) : ToolingEvent

// ---------------------------------------------------------------------------
// Builds (§4.3).
// ---------------------------------------------------------------------------

/** Lance un build : tâches, arguments, identifiant de suivi. */
@Serializable
@SerialName("build_request")
public data class BuildRequest(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
    public val tasks: List<String>,
    public val arguments: List<String> = emptyList(),
    public val buildId: String,
) : ToolingRequest

/** Le build a commencé (identifiant + tâches demandées). */
@Serializable
@SerialName("build_started")
public data class BuildStarted(
    override val id: String,
    override val protocolVersion: Int,
    public val buildId: String,
    public val tasks: List<String>,
) : ToolingEvent

/** Une ligne de sortie (stdout/stderr) — unité minimale, jamais confluentée. */
@Serializable
@SerialName("build_output")
public data class BuildOutput(
    override val id: String,
    override val protocolVersion: Int,
    public val buildId: String,
    public val stream: StreamKind,
    public val line: String,
    public val timestampMs: Long,
) : ToolingEvent

/** Une tâche du build démarre. */
@Serializable
@SerialName("task_started")
public data class TaskStarted(
    override val id: String,
    override val protocolVersion: Int,
    public val buildId: String,
    public val taskPath: String,
) : ToolingEvent

/**
 * Une tâche du build se termine (réussie, sautée ou non).
 *
 * v3 : `durationMs` (durée effective mesurée par l'opération Gradle,
 * symétrique de [BuildFinished.durationMs]) et `skipped` (une tâche
 * SAUTÉE n'est ni un échec ni un vrai travail — l'affichage s'honore à la
 * distinguer, comme la console d'Android Studio) ; défauts compatibles
 * avec les frames v2 (`ignoreUnknownKeys` + valeurs par défaut).
 */
@Serializable
@SerialName("task_finished")
public data class TaskFinished(
    override val id: String,
    override val protocolVersion: Int,
    public val buildId: String,
    public val taskPath: String,
    public val succeeded: Boolean,
    public val durationMs: Long = 0,
    public val skipped: Boolean = false,
) : ToolingEvent

/** Résultat final d'un build. */
@Serializable
@SerialName("build_finished")
public data class BuildFinished(
    override val id: String,
    override val protocolVersion: Int,
    public val buildId: String,
    public val succeeded: Boolean,
    public val durationMs: Long,
    public val failureMessage: String? = null,
    /**
     * `true` quand le build a été ANNULÉ à la demande de l'utilisateur
     * (v0.39.1, correctif n°5 — distingué de l'échec pour la pastille du
     * BottomSheet : un build annulé n'est PAS un échec, l'UI affiche
     * l'état `ANNULE` atténué, pas le rouge `ECHOUE`). Quand `succeeded`
     * est `false` et `cancelled` est `false`, l'échec est RÉEL.
     */
    public val cancelled: Boolean = false,
    /**
     * Total des tâches ACTIONNABLES du build (v0.39.1, correctif n°4 —
     * synthèse d'Android Studio « N actionable tasks ») : extrait de la
     * dernière ligne stdout de Gradle. `null` quand la ligne n'a pas été
     * observée (build échoué avant la fin, sortie non détectable).
     */
    public val actionableTasks: Int? = null,
    /**
     * Tâches réellement EXÉCUTÉES (v0.39.1) : « N executed » dans la
     * synthèse de Gradle. `null` quand inconnu.
     */
    public val executedTasks: Int? = null,
    /**
     * Tâches À JOUR (incrémental, v0.39.1) : « K up-to-date » dans la
     * synthèse de Gradle. `null` quand la version incrémentale n'a pas
     * été imprimée (build sans cache, premier lancement).
     */
    public val upToDateTasks: Int? = null,
) : ToolingEvent

/** Progression générique (opérations Gradle, pas seulement tâches).
 *  v4 (addendum §6) : porte aussi un DÉTAIL DE TÉLÉCHARGEMENT structuré
 *  quand l'événement provient d'un `FILE_DOWNLOAD` — les téléchargements
 *  sont visibles pour TOUTE action Gradle (build, sync, classpath,
 *  listage), le message textuel reste pour les statuts génériques. */
@Serializable
@SerialName("progress_event")
public data class ProgressEvent(
    override val id: String,
    override val protocolVersion: Int,
    public val buildId: String,
    public val message: String,
    /** Détail structuré du téléchargement en cours — `null` pour un statut
     *  textuel simple. */
    public val telechargement: DetailTelechargement? = null,
) : ToolingEvent

/**
 * Détail d'un téléchargement observé pendant une action Gradle (v4,
 * addendum §6) : nom d'artefact SANS donnée personnelle (dernier segment
 * de l'URI), octets reçus/total si connus, compteur d'éléments terminés.
 * Émis par l'écouteur commun, débit borné (au plus 5 événements/s par
 * élément) pour ne pas saturer le bus.
 */
@Serializable
@SerialName("detail_telechargement")
public data class DetailTelechargement(
    /** Élément téléchargé (dernier segment de l'URI — jamais un chemin local). */
    public val element: String,
    /** Octets reçus pour CET élément (au fil de l'eau). */
    public val octetsRecus: Long = 0,
    /** Octets totaux de l'élément si connus — `null` sinon. */
    public val octetsTotal: Long? = null,
    /** `true` quand l'élément est terminé (réussi OU échoué). */
    public val termine: Boolean = false,
    /** Durée du téléchargement à sa fin (ms). */
    public val dureeMs: Long = 0,
    /** Compteur d'éléments terminés de l'action (n) — `null` si sans objet. */
    public val compteur: Int? = null,
)

/** Annule le build [buildId] (propagée vers `CancellationTokenSource`). */
@Serializable
@SerialName("cancel_request")
public data class CancelRequest(
    override val id: String,
    override val protocolVersion: Int,
    public val buildId: String,
) : ToolingRequest

// ---------------------------------------------------------------------------
// Synchronisation et modèles (§5.3 — Resilient Sync).
// ---------------------------------------------------------------------------

/** Synchronise un projet (IDE-like : modèles, dépendances, tâches).
 *  v4 : les arguments réglés (`--offline`, arguments libres) voyagent
 *  avec la requête — la sync ne les ignorait pas, elles s'appliquent
 *  désormais à la configuration du build. */
@Serializable
@SerialName("sync_request")
public data class SyncRequest(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
    public val gradleVersion: String? = null,
    /** Arguments Gradle de la sync (réglages tooling de l'utilisateur). */
    public val arguments: List<String> = emptyList(),
) : ToolingRequest

/**
 * La synchronisation a commencé (étape 32, ADR 0057) : émis AVANT la
 * résolution des modèles — symétrique de [BuildStarted] pour les builds,
 * l'app voit le départ venir DU serveur, pas seulement de son propre
 * geste (l'en-tête et la notification se posent sur un fait, pas sur une
 * présomption).
 */
@Serializable
@SerialName("sync_started")
public data class SyncStarted(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
) : ToolingEvent

/** Synchronisation terminée (réussie ou échec sec). */
@Serializable
@SerialName("sync_result")
public data class SyncResult(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
    public val succeeded: Boolean,
    public val durationMs: Long,
    public val failureMessage: String? = null,
) : ToolingEvent

/**
 * Synchronisation **partielle** (Resilient Sync, §5.3) : les modèles déjà
 * résolus sont livrés malgré l'échec des autres — mieux qu'un échec sec.
 */
@Serializable
@SerialName("partial_sync_result")
public data class PartialSyncResult(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
    public val resolvedModels: List<String>,
    public val failedModels: List<String>,
) : ToolingEvent

/**
 * Phase d'une synchronisation (v3 ; v4 : phases RÉELLES ; v6 — prompt de
 * suivi §2 : suppression du concept « sautée / En cache ») : étape
 * intermédiaire structurée entre [SyncStarted] et
 * [SyncResult]/[PartialSyncResult] — la sync cesse d'être une boîte noire
 * « en cours / terminée », l'app affiche CE que l'orchestrateur fait comme
 * la console d'Android Studio déroule ses phases.
 *
 * Une phase est annoncée DEUX fois : au départ ([terminee] `false`) et à
 * la fin ([terminee] `true`, [dureeMs] de la phase) — le client en fait des
 * lignes de console du canal Sync. Les libellés restent côté client
 * (l'état voyage structuré, jamais localisé par le serveur).
 *
 * v4 : les détails de progression ([octetsRecus], [element], [compteur])
 * alimentent le sous-titre de l'en-tête et les sous-lignes de l'arbre de
 * la console — un téléchargement se VOIT, une phase ne reste muette que
 * si Gradle lui-même est muet. Champs à défaut : un décodeur v3 ignore
 * les inconnus, un émetteur v4 les omet quand l'information manque.
 *
 * v6 (prompt de suivi §2) : une phase qui n'a PAS LIEU n'est plus émise
 * du tout — fini le drapeau `sautee` et le rendu « En cache » côté client.
 * Le catalogue affiché est **construit pour la sync en cours** : une
 * étape non concernée n'existe pas dans la liste (cf. ADR 0073 à venir).
 */
@Serializable
@SerialName("sync_progress")
public data class SyncProgress(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
    public val phase: SyncPhase,
    public val terminee: Boolean = false,
    public val dureeMs: Long = 0,
    /** Octets reçus cumulés pour la phase (téléchargement) — 0 si sans objet. */
    public val octetsRecus: Long = 0,
    /** Octets totaux si CONNUS (souvent inconnus côté Tooling API) — sinon `null`. */
    public val octetsTotal: Long? = null,
    /** Élément courant (nom d'artefact/URL SANS donnée personnelle, projet). */
    public val element: String? = null,
    /** Compteur d'éléments terminés de la phase (n) — `null` si sans objet. */
    public val compteur: Int? = null,
    /** Total d'éléments de la phase (N) si CONNU — `null` sinon. */
    public val total: Int? = null,
) : ToolingEvent

/**
 * Phase énumérée d'une synchronisation (v4 — RÉELLES, dans l'ordre du
 * déroulé) — miroir câble de l'étape domaine, l'UI choisit ses libellés.
 * L'ancienne v3 mentait : `CONNEXION` (le `connect()` ne télécharge rien,
 * la distribution se résout au premier `get()`), `MODELE_GRADLE`/
 * `MODELE_IDEA` (deux requêtes séparées = deux configurations du build).
 */
@Serializable
public enum class SyncPhase {
    /** Vérifications locales AVANT toute requête : dossier, wrapper
     *  (version), JDK, distribution déjà en cache. */
    OUTILS,

    /** Résolution de la distribution Gradle (téléchargement puis
     *  décompression) — sa propre phase, plus noyée dans « connexion ». */
    DISTRIBUTION,

    /** Démarrage du daemon Gradle (première requête sur un daemon froid). */
    DAEMON,

    /** Configuration des projets du build (événements
     *  PROJECT_CONFIGURATION de la Tooling API, un par projet). */
    CONFIGURATION,

    /** Résolution du modèle `GradleProject` (tâches) — dans l'action
     *  UNIQUE v4, plus de seconde configuration du build. */
    MODELE_TACHES,

    /** Résolution du modèle `IdeaProject` (structure IDE, dépendances) —
     *  dans la MÊME action unique. */
    MODELE_IDE,

    /** Téléchargement des dépendances (événements FILE_DOWNLOAD de la
     *  Tooling API : artefact, octets reçus, compteur n/N). */
    DEPENDANCES,

    /** Préparation des classpaths LSP depuis `IdeaProject` — publiée
     *  AVANT [SyncResult] : « Synchronisé » ne s'affiche qu'après. */
    CLASSPATHS,
}

/** Liste les tâches d'un projet (sélecteur « Exécuter »). */
@Serializable
@SerialName("tasks_request")
public data class TasksRequest(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
) : ToolingRequest

/** Tâche d'un projet, prête à afficher dans un sélecteur. */
@Serializable
@SerialName("task_info")
public data class TaskInfo(
    public val path: String,
    public val group: String? = null,
    public val displayName: String,
)

/** Réponse de [TasksRequest]. */
@Serializable
@SerialName("tasks_result")
public data class TasksResult(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
    public val tasks: List<TaskInfo>,
) : ToolingEvent

/** Demande le graphe de dépendances d'un projet. */
@Serializable
@SerialName("dependencies_request")
public data class DependenciesRequest(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
) : ToolingRequest

/** Dépendance résolue (module + configuration). */
@Serializable
@SerialName("dependency_info")
public data class DependencyInfo(
    public val module: String,
    public val configuration: String? = null,
)

/** Réponse de [DependenciesRequest]. */
@Serializable
@SerialName("dependencies_result")
public data class DependenciesResult(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
    public val dependencies: List<DependencyInfo>,
) : ToolingEvent

/** Demande un modèle de la Tooling API (projet, IDE générique…). */
@Serializable
@SerialName("model_request")
public data class ModelRequest(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
) : ToolingRequest

// ---------------------------------------------------------------------------
// Classpaths (ADR 0058 — préparation LSP, comme Android Studio prépare
// l'index du projet à la sync).
// ---------------------------------------------------------------------------

/**
 * Type d'une entrée de classpath (ADR 0058) : ce que les LSP trouveront
 * sur leur chemin — bytecode compilé (jar), bibliothèque Android (aar),
 * dossier de classes ou module frère porté par son nom.
 */
@Serializable
public enum class ClasspathKind {
    /** Archive JAR (bytecode compilé). */
    @SerialName("jar")
    JAR,

    /** Archive AAR Android (bytecode + ressources). */
    @SerialName("aar")
    AAR,

    /** Dossier de classes (sortie d'un module ou dossier exposé). */
    @SerialName("dossier")
    DOSSIER,

    /** Dépendance vers un module frère (résolu par son nom). */
    @SerialName("module")
    MODULE,
}

/**
 * Demande le classpath compilé de chaque module du projet (ADR 0058) :
 * répertoires sources et entrées de classpath — le client l'émet juste
 * après une sync réussie puis PERSISTE la réponse pour que les LSP s'en
 * servent le moment venu, comme Android Studio prépare son index.
 */
@Serializable
@SerialName("classpath_request")
public data class ClasspathRequest(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
    /** Arguments Gradle de la résolution (v4 : `--offline` et arguments
     *  réglés s'appliquent AUSSI au classpath). */
    public val arguments: List<String> = emptyList(),
) : ToolingRequest

/**
 * Une entrée du classpath d'un module (ADR 0058) : chemin du jar, de
 * l'AAR, du dossier de classes — ou NOM du module frère quand
 * [kind] vaut [ClasspathKind.MODULE] ; [sources] porte le jar de
 * sources attaché quand Gradle le connaît.
 *
 * @property path fichier/dossier concerné, ou nom du module frère.
 * @property kind nature de l'entrée.
 * @property scope portée de la dépendance (`compile`, `test`…), si connue.
 * @property sources jar de sources attaché, `null` si aucun.
 */
@Serializable
@SerialName("classpath_entry")
public data class ClasspathEntry(
    public val path: String,
    public val kind: ClasspathKind,
    public val scope: String? = null,
    public val sources: String? = null,
)

/**
 * Classpath d'UN module (ADR 0058) : répertoires sources (le module
 * et ses tests) et entrées compilées — assez pour qu'un LSP compile,
 * complète et navigue.
 *
 * v0.40.1 (prompt de suivi §4) : statistiques pré-calculées par module
 * pour l'affichage des sous-lignes « :app · 312 jars · 4 sources ·
 * variante debug ». Les champs sont optionnels (compatibilité ascendante
 * — un serveur ancien ne les envoie pas, un client nouveau les affiche
 * pas ; un serveur nouveau les envoie, un client ancien les ignore via
 * `ignoreUnknownKeys`).
 *
 * @property name nom du module Gradle (ex. `:app`).
 * @property sourceDirs répertoires sources absolus.
 * @property entries entrées du classpath compilé.
 * @property nbJars nombre de JARs résolus (v0.40.1 §4) — `null` si non
 *           calculé par le serveur.
 * @property nbAars nombre d'AARs résolus (v0.40.1 §4) — `null` si non
 *           calculé.
 * @property nbSources nombre de répertoires sources (v0.40.1 §4) —
 *           `null` si non calculé.
 * @property varianteAndroid variante Android retenue (ex. `debug`,
 *           `release`) — `null` pour un module non-Android ou non
 *           déterminable.
 * @property nbDependancesProjet nombre de dépendances vers des modules
 *           frères (v0.40.1 §4) — `null` si non calculé.
 * @property fichiersGeneres fichiers générés trouvés (R, BuildConfig,
 *           KSP/kapt — v0.40.1 §4) — `null` si non calculé ; vide si
 *           aucun.
 * @property androidJar chemin vers `android.jar` (compileSdk, v0.40.1
 *           §4) — `null` pour un module non-Android.
 * @property ignore `true` si le module est ignoré par CodeIDE (v0.40.1
 *           §4) — par défaut `false`.
 * @property raisonIgnore raison de l'ignorance (v0.40.1 §4) — `null` si
 *           non ignoré.
 * @property avertissements avertissements sur ce module (dépendance non
 *           résolue, AAR non extrait — v0.40.1 §4) — `null` si non
 *           calculé ; vide si aucun.
 */
@Serializable
@SerialName("classpath_module")
public data class ClasspathModule(
    public val name: String,
    public val sourceDirs: List<String>,
    public val entries: List<ClasspathEntry>,
    public val nbJars: Int? = null,
    public val nbAars: Int? = null,
    public val nbSources: Int? = null,
    public val varianteAndroid: String? = null,
    public val nbDependancesProjet: Int? = null,
    public val fichiersGeneres: List<String>? = null,
    public val androidJar: String? = null,
    public val ignore: Boolean = false,
    public val raisonIgnore: String? = null,
    public val avertissements: List<String>? = null,
)

/** Réponse de [ClasspathRequest] : classpath de chaque module du projet. */
@Serializable
@SerialName("classpath_result")
public data class ClasspathResult(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
    public val modules: List<ClasspathModule>,
) : ToolingEvent

// ---------------------------------------------------------------------------
// Surveillance (§4.6) et santé.
// ---------------------------------------------------------------------------

/** Demande un instantané du tas du process orchestrateur. */
@Serializable
@SerialName("heap_request")
public data class HeapRequest(
    override val id: String,
    override val protocolVersion: Int,
) : ToolingRequest

/** Instantané du tas du process orchestrateur (HeapMonitor, §4.6). */
@Serializable
@SerialName("heap_event")
public data class HeapEvent(
    override val id: String,
    override val protocolVersion: Int,
    public val usedMb: Long,
    public val maxMb: Long,
) : ToolingEvent

/** Sonde de santé (§5.4 : ping/pong 5 s, timeout 15 s). */
@Serializable
@SerialName("ping")
public data class PingMessage(
    override val id: String,
    override val protocolVersion: Int,
) : ToolingRequest

/** Réponse à [PingMessage]. */
@Serializable
@SerialName("pong")
public data class PongMessage(
    override val id: String,
    override val protocolVersion: Int,
) : ToolingEvent

// ---------------------------------------------------------------------------
// Diagnostics et erreurs.
// ---------------------------------------------------------------------------

/**
 * Diagnostic remonté vers l'éditeur (§3.2) : gravité, position fichier
 * et source (compilateur, tâche…) — `session.setDiagnostics` (cel-ui)
 * consomme la traduction de ce message.
 */
@Serializable
@SerialName("diagnostic")
public data class Diagnostic(
    override val id: String,
    override val protocolVersion: Int,
    public val severity: DiagnosticSeverity,
    public val file: String,
    public val line: Long,
    public val column: Long,
    public val message: String,
    public val source: String,
) : ToolingEvent

/**
 * Erreur typée en réponse à une requête (§3.2) : [requestId] rappelle la
 * requête fautive, [code] porte la décision machine, [message] humain.
 */
@Serializable
@SerialName("error_response")
public data class ErrorResponse(
    override val id: String,
    override val protocolVersion: Int,
    public val requestId: String,
    public val code: ErrorCode,
    public val message: String,
    public val detail: String? = null,
) : ToolingEvent
