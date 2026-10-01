package jo.codeide.tooling.client

import jo.codeide.core.domain.ClasspathProjet
import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EntreeClasspath
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.ModuleClasspath
import jo.codeide.core.domain.SeveriteDiagnostic
import jo.codeide.core.domain.TypeEntreeClasspath
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.ToolingReason
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathResult
import jo.codeide.tooling.protocol.Diagnostic
import jo.codeide.tooling.protocol.DiagnosticSeverity
import jo.codeide.tooling.protocol.ErrorCode
import jo.codeide.tooling.protocol.ErrorResponse
import jo.codeide.tooling.protocol.StreamKind

/**
 * Traductions protocole → domaine du client tooling (extraites de
 * [GradleApiImpl] — v0.45.1 : fonctions PURES, sans état, la classe pompait
 * au-delà de la limite de complexité après l'ajout du routage des statuts
 * textuels). Un seul endroit pour lire « ce que vaut un événement du
 * protocole côté domaine » — le reste du client route, ces fonctions
 * traduisent.
 */

internal fun StreamKind.versFluxDomaine(): FluxSortieBuild =
    when (this) {
        StreamKind.STDOUT -> FluxSortieBuild.STDOUT
        StreamKind.STDERR -> FluxSortieBuild.STDERR
    }

internal fun Diagnostic.versDiagnosticDomaine(): DiagnosticBuild =
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

/** Traduit un classpath du protocole vers le domaine (ADR 0058).
 *  v0.40.1 (prompt de suivi §4) : propage les statistiques par module. */
internal fun ClasspathResult.versClasspathDomaine(): ClasspathProjet =
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
                    nbJars = module.nbJars,
                    nbAars = module.nbAars,
                    nbSources = module.nbSources,
                    varianteAndroid = module.varianteAndroid,
                    nbDependancesProjet = module.nbDependancesProjet,
                    fichiersGeneres = module.fichiersGeneres,
                    androidJar = module.androidJar,
                    ignore = module.ignore,
                    raisonIgnore = module.raisonIgnore,
                    avertissements = module.avertissements,
                )
            },
    )

internal fun ErrorResponse.versErreurDomaine(): AppError.Tooling =
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
