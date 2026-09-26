package jo.codeide.tooling.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Échantillons canoniques partagés par les tests du protocole : une
 * instance par type de message, exactement celles des fichiers dorés
 * (`les fichiers dorés de `golden` (un par message)`).
 */
internal object EchantillonsMessages {
    const val ID_REQUETE = "req-42"
    const val ID_EVENEMENT = "evt-43"
    const val VERSION = GradleProtocol.PROTOCOL_VERSION

    /** Requêtes (app → process JVM) — l'ordre suit le catalogue §3.2. */
    val requetes: List<ToolingRequest> =
        listOf(
            HelloRequest(ID_REQUETE, VERSION, clientVersion = "0.26.0", handshakeSecret = "secret-de-test"),
            BuildRequest(
                ID_REQUETE,
                VERSION,
                projectDir = "/projets/demo",
                tasks = listOf("build", "test"),
                arguments = listOf("--stacktrace"),
                buildId = "build-7",
            ),
            SyncRequest(ID_REQUETE, VERSION, projectDir = "/projets/demo", gradleVersion = "9.7.1"),
            TasksRequest(ID_REQUETE, VERSION, projectDir = "/projets/demo"),
            DependenciesRequest(ID_REQUETE, VERSION, projectDir = "/projets/demo"),
            ModelRequest(ID_REQUETE, VERSION, projectDir = "/projets/demo"),
            CancelRequest(ID_REQUETE, VERSION, buildId = "build-7"),
            HeapRequest(ID_REQUETE, VERSION),
            PingMessage(ID_REQUETE, VERSION),
        )

    /** Événements (process JVM → app) — l'ordre suit le catalogue §3.2. */
    val evenements: List<ToolingEvent> =
        listOf(
            HelloResponse(
                ID_EVENEMENT,
                VERSION,
                serverVersion = "0.26.0",
                gradleToolingApiVersion = "9.7.1",
                supportedFeatures = setOf("build", "sync", "tasks"),
            ),
            BuildStarted(ID_EVENEMENT, VERSION, buildId = "build-7", tasks = listOf("build")),
            BuildOutput(
                ID_EVENEMENT,
                VERSION,
                buildId = "build-7",
                stream = StreamKind.STDOUT,
                line = "BUILD SUCCESSFUL",
                timestampMs = 1_727_100_000_000,
            ),
            TaskStarted(ID_EVENEMENT, VERSION, buildId = "build-7", taskPath = ":app:compileKotlin"),
            TaskFinished(ID_EVENEMENT, VERSION, buildId = "build-7", taskPath = ":app:compileKotlin", succeeded = true),
            BuildFinished(ID_EVENEMENT, VERSION, buildId = "build-7", succeeded = true, durationMs = 1_250),
            ProgressEvent(ID_EVENEMENT, VERSION, buildId = "build-7", message = "Configuration cache reuse"),
            SyncStarted(ID_EVENEMENT, VERSION, projectDir = "/projets/demo"),
            SyncResult(ID_EVENEMENT, VERSION, projectDir = "/projets/demo", succeeded = true, durationMs = 4_200),
            PartialSyncResult(
                ID_EVENEMENT,
                VERSION,
                projectDir = "/projets/demo",
                resolvedModels = listOf("idea", "tasks"),
                failedModels = listOf("dependencies"),
            ),
            TasksResult(
                ID_EVENEMENT,
                VERSION,
                projectDir = "/projets/demo",
                tasks = listOf(TaskInfo(path = ":app:build", group = "build", displayName = "build")),
            ),
            DependenciesResult(
                ID_EVENEMENT,
                VERSION,
                projectDir = "/projets/demo",
                dependencies =
                    listOf(
                        DependencyInfo(
                            module = "org.jetbrains.kotlin:kotlin-stdlib",
                            configuration = "implementation",
                        ),
                    ),
            ),
            HeapEvent(ID_EVENEMENT, VERSION, usedMb = 128, maxMb = 512),
            Diagnostic(
                ID_EVENEMENT,
                VERSION,
                severity = DiagnosticSeverity.ERROR,
                file = "/projets/demo/src/Main.kt",
                line = 12,
                column = 5,
                message = "Unresolved reference: truc",
                source = "kotlinc",
            ),
            PongMessage(ID_EVENEMENT, VERSION),
            ErrorResponse(
                ID_EVENEMENT,
                VERSION,
                requestId = ID_REQUETE,
                code = ErrorCode.TIMEOUT,
                message = "Délai de synchronisation dépassé",
                detail = "5 minutes",
            ),
        )
}
