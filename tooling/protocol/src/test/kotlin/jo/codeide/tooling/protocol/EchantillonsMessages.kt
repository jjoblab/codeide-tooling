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
            SyncRequest(
                ID_REQUETE,
                VERSION,
                projectDir = "/projets/demo",
                gradleVersion = "9.7.1",
                arguments = listOf("--offline"),
            ),
            TasksRequest(ID_REQUETE, VERSION, projectDir = "/projets/demo"),
            DependenciesRequest(ID_REQUETE, VERSION, projectDir = "/projets/demo"),
            ClasspathRequest(ID_REQUETE, VERSION, projectDir = "/projets/demo", arguments = listOf("--offline")),
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
            TaskFinished(
                ID_EVENEMENT,
                VERSION,
                buildId = "build-7",
                taskPath = ":app:compileKotlin",
                succeeded = true,
                durationMs = 2_345,
                skipped = false,
            ),
            BuildFinished(
                ID_EVENEMENT,
                VERSION,
                buildId = "build-7",
                succeeded = true,
                durationMs = 1_250,
                cancelled = false,
                actionableTasks = 37,
                executedTasks = 2,
                upToDateTasks = 35,
            ),
            ProgressEvent(
                ID_EVENEMENT,
                VERSION,
                buildId = "build-7",
                message = "Téléchargement kotlin-stdlib-2.2.10.jar",
                telechargement =
                    DetailTelechargement(
                        element = "kotlin-stdlib-2.2.10.jar",
                        octetsRecus = 1_769_000,
                        octetsTotal = 1_769_000,
                        termine = true,
                        dureeMs = 850,
                        compteur = 3,
                    ),
            ),
            SyncStarted(ID_EVENEMENT, VERSION, projectDir = "/projets/demo"),
            // v0.47.0 : le résultat PORTE les tâches résolues par l'action
            // — le client arme le bouton Tâches sur CE fait, sans second
            // aller-retour de listage (champ optionnel : absent du câble
            // pour un serveur antérieur, la liste retombe à vide).
            SyncResult(
                ID_EVENEMENT,
                VERSION,
                projectDir = "/projets/demo",
                succeeded = true,
                durationMs = 4_200,
                taches =
                    listOf(
                        TaskInfo(path = ":app:assembleDebug", group = "build", displayName = "assembleDebug"),
                        TaskInfo(path = ":app:clean", group = "build", displayName = "clean"),
                    ),
            ),
            PartialSyncResult(
                ID_EVENEMENT,
                VERSION,
                projectDir = "/projets/demo",
                resolvedModels = listOf("idea", "tasks"),
                failedModels = listOf("dependencies"),
            ),
            SyncProgress(
                ID_EVENEMENT,
                VERSION,
                projectDir = "/projets/demo",
                phase = SyncPhase.DEPENDANCES,
                terminee = false,
                dureeMs = 0,
                octetsRecus = 44_040_192,
                element = "kotlin-stdlib-2.2.10.jar",
                compteur = 3,
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
            ClasspathResult(
                ID_EVENEMENT,
                VERSION,
                projectDir = "/projets/demo",
                modules =
                    listOf(
                        ClasspathModule(
                            name = ":app",
                            sourceDirs = listOf("/projets/demo/app/src/main/java"),
                            entries =
                                listOf(
                                    ClasspathEntry(path = ":lib", kind = ClasspathKind.MODULE, scope = "compile"),
                                    ClasspathEntry(
                                        path =
                                            "/cache/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/" +
                                                "kotlin-stdlib-2.2.10.jar",
                                        kind = ClasspathKind.JAR,
                                        scope = "compile",
                                        sources =
                                            "/cache/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/" +
                                                "kotlin-stdlib-2.2.10-sources.jar",
                                    ),
                                    ClasspathEntry(
                                        path = "/cache/modules-2/files-2.1/androidx.core/core/1.17.0/core-1.17.0.aar",
                                        kind = ClasspathKind.AAR,
                                        scope = "compile",
                                    ),
                                ),
                            // v0.40.1 (prompt de suivi §4) : statistiques par
                            // module pré-calculées côté serveur.
                            nbJars = 1,
                            nbAars = 1,
                            nbSources = 1,
                            varianteAndroid = "debug",
                            nbDependancesProjet = 1,
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
