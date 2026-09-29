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
