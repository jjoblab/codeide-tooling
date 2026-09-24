package jo.codeide.tooling.api

import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.HeapEvent
import jo.codeide.tooling.protocol.TaskInfo
import jo.codeide.tooling.protocol.TasksResult
import jo.codeide.tooling.protocol.Diagnostic as DiagnosticProtocole

// Traduction protocol → api (G2) : le format câble est figé par les fichiers
// dorés de G1, les modèles de l'api évoluent librement — ces mappers sont la
// frontière unique entre les deux. Le client Android (G3) n'interprète
// jamais un message brut : il consomme ces modèles.

/** Une ligne de sortie de build (§5.3 — `observeBuildOutput`). */
public fun BuildOutput.versLigneSortie(): LigneSortieBuild =
    LigneSortieBuild(
        buildId = buildId,
        flux = stream,
        ligne = line,
        horodatageMs = timestampMs,
    )

/** Le résultat terminal d'un build, sans distinction annulé/échoué (le
 * client la détient : c'est lui qui a demandé l'annulation). */
public fun BuildFinished.versEtatBuild(): EtatBuild =
    EtatBuild(
        buildId = buildId,
        statut = if (succeeded) StatutBuild.REUSSI else StatutBuild.ECHOUE,
        dureeMs = durationMs,
        messageEchec = failureMessage,
    )

/** Un instantané du tas de l'orchestrateur (§4.6). */
public fun HeapEvent.versInstantaneTas(): InstantaneTas =
    InstantaneTas(
        moUtilises = usedMb,
        moMax = maxMb,
    )

/** Une tâche du sélecteur « Exécuter » (§5.3). */
public fun TaskInfo.versInfoTache(): InfoTache =
    InfoTache(
        chemin = path,
        groupe = group,
        nomAffiche = displayName,
    )

/** Toutes les tâches d'un [TasksResult], prêtes à afficher. */
public fun TasksResult.versInfosTaches(): List<InfoTache> = tasks.map { it.versInfoTache() }

/** Un diagnostic de code (§3.2/§6). */
public fun DiagnosticProtocole.versDiagnostic(): Diagnostic =
    Diagnostic(
        severite = severity,
        fichier = file,
        ligne = line,
        colonne = column,
        message = message,
        source = source,
    )
