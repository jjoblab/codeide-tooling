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

/** Une tâche du build se termine (réussie ou non). */
@Serializable
@SerialName("task_finished")
public data class TaskFinished(
    override val id: String,
    override val protocolVersion: Int,
    public val buildId: String,
    public val taskPath: String,
    public val succeeded: Boolean,
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
) : ToolingEvent

/** Progression générique (opérations Gradle, pas seulement tâches). */
@Serializable
@SerialName("progress_event")
public data class ProgressEvent(
    override val id: String,
    override val protocolVersion: Int,
    public val buildId: String,
    public val message: String,
) : ToolingEvent

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

/** Synchronise un projet (IDE-like : modèles, dépendances, tâches). */
@Serializable
@SerialName("sync_request")
public data class SyncRequest(
    override val id: String,
    override val protocolVersion: Int,
    public val projectDir: String,
    public val gradleVersion: String? = null,
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
 * @property name nom du module Gradle (ex. `:app`).
 * @property sourceDirs répertoires sources absolus.
 * @property entries entrées du classpath compilé.
 */
@Serializable
@SerialName("classpath_module")
public data class ClasspathModule(
    public val name: String,
    public val sourceDirs: List<String>,
    public val entries: List<ClasspathEntry>,
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
