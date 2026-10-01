package jo.codeide.tooling.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Écouteur TEXTUEL de la fenêtre daemon d'un build (v0.45.2 — retour de
 * terrain : « BUILD SUCCESSFUL in 10 s » affiché au bout de 200-300 s).
 *
 * L'EXPÉRIENCE qui a fondé ce correctif (programme autonome Gradle
 * 9.7.1, mesuré à froid ET à chaud) : tout le démarrage du daemon se
 * produit DANS `newBuild().run()` — `connect()` rend un objet PARESSEUX
 * en ~300 ms, sans daemon derrière. Pendant `run()`, la Tooling API
 * émet des statuts textuels riches sur le listener NON typé :
 *
 * - `Starting Gradle Daemon` (le spawn : 60 s à 4 min sur téléphone —
 *   c'est là que passent les « 200-300 s » constatés) ;
 * - `Connecting to Gradle Daemon` (le daemon répond : la connexion est
 *   établie) ;
 * puis `Evaluate settings`, `Configure build`… déjà couverts par les
 * événements typés PROJET/CONFIGURATION de la v0.45.1.
 *
 * Le [BuildHandler] n'enregistrait AUCUN listener textuel : ces statuts
 * tombaient dans le vide — la console restait MUETTE pendant toute la
 * fenêtre la plus longue du trajet mobile, puis affichait le stdout d'un
 * build déjà fini (l'« affichage progressif » tardif du signalement).
 * Android Studio, lui, écrit « Starting Gradle Daemon… » dans sa console
 * au moment même où ça se produit : ce listener rétablit la parité.
 *
 * Filtre : seuls les statuts parlant du DAEMON traversent (les autres
 * phases ont déjà leurs événements typés — publier le flot textuel entier
 * noierait la console), DÉDUPLIÉS par description (la Tooling API peut
 * répéter « Starting Gradle Daemon » sur un essai de spawn).
 *
 * Thread-safe : appelé sur les fils internes de Gradle, comme
 * [ProgressBridge] et [StreamingOutputStream].
 *
 * @param debutMs instant de départ du build (celui du chrono serveur) —
 *        le statut « Connecting » conclut la fenêtre avec le délai RÉEL
 *        « daemon Gradle connecté (X ms) », à lire comme le temps pour
 *        OBTENIR un daemon (froid : spawn complet ; chaud : aller-retour).
 * @param horloge horloge injectée (tests) ; production : système.
 * @param publier publie une ligne de statut vers la console du client.
 */
internal class EcouteurStatutDaemonBuild(
    private val debutMs: Long,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val publier: (message: String) -> Unit,
) : org.gradle.tooling.ProgressListener {
    /** Statuts déjà publiés (dédup par description). */
    private val dejaPublies = ConcurrentHashMap.newKeySet<String>()

    /** La conclusion « daemon connecté » n'est publiée qu'une fois. */
    private val fenetreConclue = AtomicBoolean(false)

    /**
     * Exemption detekt ciblée (règle 16) : ReturnCount — clauses de garde
     * (description absente, hors daemon, doublon) retournant chacune sans
     * effet ; même justification que [ProgressBridge.statusChanged].
     */
    @Suppress("ReturnCount")
    override fun statusChanged(evenement: org.gradle.tooling.ProgressEvent) {
        val description = evenement.description
        if (description.isNullOrBlank()) return
        if (!description.contains(MOTIF_DAEMON, ignoreCase = true)) return
        // Dédup : la Tooling API répète « Starting Gradle Daemon » quand
        // elle retente un spawn — la console n'a pas besoin du doublon.
        if (!dejaPublies.add(description)) return
        if (description.contains(MOTIF_CONNEXION, ignoreCase = true)) {
            if (fenetreConclue.compareAndSet(false, true)) {
                publier("daemon Gradle connecté (${horloge() - debutMs} ms)")
                return
            }
        }
        publier(description)
    }

    private companion object {
        /** Seuls les statuts du daemon traversent (cf. KDoc du filtre). */
        const val MOTIF_DAEMON = "daemon"

        /** Statut marquant la connexion établie — conclut la fenêtre. */
        const val MOTIF_CONNEXION = "connecting"
    }
}
