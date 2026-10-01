package jo.codeide.tooling.server

/**
 * Extraction de la synthèse finale de Gradle (v0.39.1, correctif n°4) :
 * la dernière ligne stdout d'un build réussi imprimée par Gradle sous
 * la forme :
 *
 * - `37 actionable tasks: 37 executed` (build complet, sans incrémental) ;
 * - `37 actionable tasks: 2 executed, 35 up-to-date` (incrémental — la
 *   forme attendue par l'utilisateur, comparable à Android Studio).
 *
 * La ligne est OPTIONNELLE (un build échoué ne l'imprime pas, un
 * utilisateur qui redirige la sortie peut la manquer). Le [ParseurSyntheseBuild]
 * est donc PUR, best-effort : il renvoie `null` quand la ligne ne
 * correspond pas — l'appelant garde l'état précédent (aucune synthèse).
 *
 * Format accepté :
 * - `(\d+) actionable tasks: (\d+) executed(?:, (\d+) up-to-date)?`
 *
 * L'espace après les deux-points est OBLIGATOIRE (Gradle l'imprime
 * toujours). La virgule est OPTIONNELLE (les builds sans incrémental ne
 * l'impriment pas). Les espaces autour de la virgule sont tolérés.
 */
internal object ParseurSyntheseBuild {
    /**
     * Motif de la ligne de synthèse de Gradle :
     * `<N> actionable tasks: <M> executed[, <K> up-to-date]`.
     *
     * - `actionable` est le terme stable de Gradle (depuis la 4.x) ;
     * - les espaces sont obligatoires autour des deux-points (Gradle les
     *   imprime ainsi, on ne force pas de tolérance pour ne pas matcher
     *   un message utilisateur qui dirait « 2 tasks: 0 executed » — un
     *   tel message n'est pas une synthèse Gradle, c'est une
     *   conversation).
     *
     * Groupes capturés (1-based) :
     * - `1` = total des tâches actionnables ;
     * - `2` = tâches exécutées ;
     * - `3` = tâches à jour (optionnel, absent en non-incrémental).
     */
    private val MOTIF =
        Regex("""^(\d+)\s+actionable\s+tasks:\s+(\d+)\s+executed(?:\s*,\s*(\d+)\s+up-to-date)?\s*$""")

    /** Indice du groupe « total des tâches actionnables » dans [MOTIF]. */
    private const val GROUPE_ACTIONNABLES = 1

    /** Indice du groupe « tâches exécutées » dans [MOTIF]. */
    private const val GROUPE_EXECUTEES = 2

    /** Indice du groupe « tâches à jour » dans [MOTIF] (optionnel). */
    private const val GROUPE_A_JOUR = 3

    /**
     * Motif de la ligne de VERDICT de Gradle (v0.45.2 — décomposition
     * honnête des durées) : `BUILD SUCCESSFUL in 10s`, `BUILD FAILED in
     * 1m 30s`, `BUILD SUCCESSFUL in 800ms`… La durée est la partie après
     * « in », une suite de couples valeur+unité (`1m 30s`, `2m 3s 456ms`,
     * `1h 2m`). L'horloge de Gradle démarre quand le build est planifié
     * sur un daemon PRÊT : elle EXCLUT le démarrage du daemon — c'est
     * l'écart avec l'horloge client qui désigne la fenêtre aveugle
     * (retour de terrain : « in 10s » affiché au bout de 200-300 s).
     */
    private val MOTIF_VERDICT =
        Regex("""^BUILD\s+(?:SUCCESSFUL|FAILED)\s+in\s+(.+)$""", RegexOption.IGNORE_CASE)

    /** Un couple valeur+unité de la durée (`456ms`, `30s`, `1m`, `2h`). */
    private val MOTIF_DUREE = Regex("""^(\d+(?:[.,]\d+)?)\s*(ms|m|h|s)$""", RegexOption.IGNORE_CASE)

    /**
     * Analyse UNE ligne de stdout ; `null` si ce n'est pas la synthèse
     * de fin de build.
     *
     * @return un [SyntheseBuild] quand la ligne correspond, `null` sinon.
     */
    fun analyser(ligne: String): SyntheseBuild? {
        val correspondance = MOTIF.find(ligne.trim()) ?: return null
        val groupes = correspondance.groupValues
        // `toIntOrNull` défensif : la regex ne capte QUE des chiffres, mais
        // un jour où le motif évoluerait (champ texte), on ne planterait pas.
        val actionable = groupes[GROUPE_ACTIONNABLES].toIntOrNull()
        val executees = groupes[GROUPE_EXECUTEES].toIntOrNull()
        // Les deux comptes principaux sont OBLIGATOIRES — sans eux, pas de
        // synthèse exploitable. Le compte « à jour » est OPTIONNEL (absent
        // en non-incrémental) — on le lit seulement si les principaux sont
        // valides, ce qui réduit à deux retours anticipés (la regex sans
        // capture, puis l'absence d'un compte obligatoire).
        return if (actionable != null && executees != null) {
            SyntheseBuild(
                actionableTasks = actionable,
                executedTasks = executees,
                upToDateTasks = groupes[GROUPE_A_JOUR].toIntOrNull(),
            )
        } else {
            null
        }
    }

    /**
     * Analyse UNE ligne de stdout ; `null` si ce n'est pas un verdict
     * de fin de build AVEC durée (v0.45.2).
     *
     * Gradle formate ses durées en couples valeur+unité séparés par des
     * espaces : `800ms`, `6s`, `1m 30s`, `2m 3s 456ms`, `1h 2m`. Les
     * valeurs décimales (ex. `1.5s`) sont acceptées, la virgule
     * française aussi (`1,5s`) — Gradle n'imprime qu'entier en pratique,
     * la tolérance est de la robustesse gratuite.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — clauses de garde
     * typées (pas un verdict, jeton illisible, valeur illisible) retournant
     * chacune `null` ; même justification que [analyser].
     *
     * @return la durée RAPPORTÉE PAR GRADLE en millisecondes (son
     *         horloge à LUI — daemon exclu) quand la ligne est un
     *         verdict, `null` sinon.
     */
    @Suppress("ReturnCount")
    fun analyserDureeMs(ligne: String): Long? {
        val duree = MOTIF_VERDICT.find(ligne.trim())?.groupValues?.get(1) ?: return null
        var total = 0.0
        var vu = false
        duree.trim().split(Regex("\\s+")).forEach { jeton ->
            val correspondance = MOTIF_DUREE.find(jeton) ?: return null
            val valeur = correspondance.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            total +=
                when (correspondance.groupValues[2].lowercase()) {
                    "ms" -> valeur
                    "s" -> valeur * MS_PAR_SECONDE
                    "m" -> valeur * MS_PAR_MINUTE
                    else -> valeur * MS_PAR_HEURE
                }
            vu = true
        }
        return if (vu) total.toLong() else null
    }

    /** Millisecondes par seconde (définition de l'unité). */
    private const val MS_PAR_SECONDE = 1_000.0

    /** Millisecondes par minute (définition de l'unité). */
    private const val MS_PAR_MINUTE = 60_000.0

    /** Millisecondes par heure (définition de l'unité). */
    private const val MS_PAR_HEURE = 3_600_000.0
}

/**
 * Synthèse extraite de la dernière ligne stdout de Gradle (v0.39.1).
 *
 * @property actionableTasks total des tâches actionnables.
 * @property executedTasks tâches réellement exécutées.
 * @property upToDateTasks tâches à jour (`null` quand Gradle n'imprime
 *           pas la partie incrémentale — build sans cache, premier
 *           lancement).
 */
internal data class SyntheseBuild(
    val actionableTasks: Int,
    val executedTasks: Int,
    val upToDateTasks: Int?,
)

/**
 * Conclusion d'un build extraite du stdout au FIL DE L'EAU (v0.45.2) :
 * l'observateur de [StreamingOutputStream] y écrit DEPUIS UN FIL DE
 * GRADLE pendant que le build tourne, [BuildHandler.publierFin] la lit
 * à la reprise — d'où les atomiques (visibilité inter-fils, même
 * discipline que l'AtomicReference qu'ils remplacent).
 *
 * @property synthese comptes de tâches de la ligne « N actionable tasks ».
 * @property dureeRapporteeMs durée RAPPORTÉE PAR GRADLE par la ligne
 *           « BUILD SUCCESSFUL/FAILED in Xs » (son horloge à LUI, daemon
 *           exclu) ; -1 tant qu'aucun verdict n'a été observé.
 */
internal class ConclusionStdout {
    /** Comptes de tâches (« N actionable tasks: M executed[, K up-to-date] »). */
    val synthese =
        java.util.concurrent.atomic
            .AtomicReference<SyntheseBuild?>(null)

    /** Durée rapportée par Gradle (ms), -1 si non observée. */
    val dureeRapporteeMs =
        java.util.concurrent.atomic
            .AtomicLong(-1)

    /** Enregistre UNE ligne de stdout : synthèse et/ou durée si elle correspond. */
    fun enregistrer(ligne: String) {
        ParseurSyntheseBuild.analyser(ligne)?.let { synthese.set(it) }
        ParseurSyntheseBuild.analyserDureeMs(ligne)?.let { dureeRapporteeMs.set(it) }
    }
}
