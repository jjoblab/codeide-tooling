package jo.codeide.tooling.api

import jo.codeide.tooling.protocol.DiagnosticSeverity
import jo.codeide.tooling.protocol.StreamKind

/**
 * Ligne de sortie d'un build (prompt Tooling, §5.3 — `observeBuildOutput`) :
 * unité minimale diffusée par l'orchestrateur, jamais conflatée.
 *
 * @property buildId identifiant du build émetteur.
 * @property flux flux d'origine (sortie standard ou erreur).
 * @property ligne contenu de la ligne, sans terminaison.
 * @property horodatageMs horodatage d'émission côté orchestrateur.
 */
public data class LigneSortieBuild(
    public val buildId: String,
    public val flux: StreamKind,
    public val ligne: String,
    public val horodatageMs: Long,
)

/** Statut d'un build vu du client (§5.3 — `observeBuildState`). */
public enum class StatutBuild {
    /** Build lancé, événements en cours d'arriver. */
    EN_COURS,

    /** Build terminé avec succès. */
    REUSSI,

    /** Build terminé en échec (compilation, tâche fautive, délai). */
    ECHOUE,

    /** Build arrêté à la demande de l'utilisateur (cancel). */
    ANNULE,
}

/**
 * État consolidé d'un build (§5.3).
 *
 * @property buildId identifiant du build.
 * @property statut statut courant.
 * @property dureeMs durée effective si le build est terminé, sinon `null`.
 * @property messageEchec message du premier échec si échec, sinon `null`.
 */
public data class EtatBuild(
    public val buildId: String,
    public val statut: StatutBuild,
    public val dureeMs: Long? = null,
    public val messageEchec: String? = null,
)

/**
 * Instantané du tas du process orchestrateur (§4.6 — `observeHeap`).
 *
 * @property moUtilises mémoire utilisée, en mégaoctets.
 * @property moMax mémoire maximum, en mégaoctets.
 */
public data class InstantaneTas(
    public val moUtilises: Long,
    public val moMax: Long,
)

/** État de la connexion entre l'app et l'orchestrateur (§5.3). */
public enum class EtatConnexion {
    /** Aucun process orchestrateur actif. */
    DECONNECTEE,

    /** Lancement en cours (JAR déployé, process démarré, socket en attente). */
    EN_CONNEXION,

    /** Handshake validé, échange de messages possible. */
    CONNECTEE,

    /** Échec définitif après épuisement des tentatives (§5.4). */
    ECHOUEE,
}

/**
 * Tâche d'un projet, prête à afficher dans un sélecteur « Exécuter »
 * (§5.3 — `tasks`).
 *
 * @property chemin chemin Gradle complet (ex. `:app:saluer`).
 * @property groupe groupe déclarant (ex. `build`), `null` si aucun.
 * @property nomAffiche nom lisible (ex. `saluer`).
 */
public data class InfoTache(
    public val chemin: String,
    public val groupe: String? = null,
    public val nomAffiche: String,
)

/**
 * Diagnostic de code remonté par le tooling (§3.2/§6) : gravité et position
 * fichier — la traduction vers `session.setDiagnostics` (cel-ui) consomme
 * ce modèle côté app.
 *
 * @property severite gravité.
 * @property fichier chemin du fichier concerné.
 * @property ligne ligne (1-based), 0 si inconnue.
 * @property colonne colonne (1-based), 0 si inconnue.
 * @property message message du compilateur ou de la tâche.
 * @property source origine (ex. `javac`, tâche Gradle).
 */
public data class Diagnostic(
    public val severite: DiagnosticSeverity,
    public val fichier: String,
    public val ligne: Long,
    public val colonne: Long,
    public val message: String,
    public val source: String,
)

/**
 * Module d'un projet résolu par l'orchestrateur (§2.1).
 *
 * @property nom nom du module Gradle (ex. `:app`).
 * @property chemin répertoire du module sur disque.
 */
public data class ModeleModule(
    public val nom: String,
    public val chemin: String,
)

/**
 * Projet résolu par l'orchestrateur (§2.1) : nom, racine, modules. Réservé
 * aux évolutions (LSP) — G2 le construit depuis `IdeaProject` côté serveur.
 *
 * @property nom nom du projet (projet racine Gradle).
 * @property chemin répertoire racine du projet.
 * @property modules modules résolus, racine incluse.
 */
public data class ModeleProjet(
    public val nom: String,
    public val chemin: String,
    public val modules: List<ModeleModule>,
)
