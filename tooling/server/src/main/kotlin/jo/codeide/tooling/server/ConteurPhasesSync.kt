package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.DetailTelechargement
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.SyncPhase
import jo.codeide.tooling.protocol.SyncProgress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Machine à états des phases RÉELLES d'une sync v4 (§3.1) : chaque phase
 * est annoncée au départ puis conclue avec sa durée MESURÉE ; les phases
 * longues reçoivent des PROGRESSIONS (octets reçus, élément courant,
 * compteur n/N) entre les deux.
 *
 * Thread-safe : les événements Tooling API arrivent sur les fils internes
 * de Gradle, le sondeur de distribution sur sa coroutine, la conclusion
 * sur le fil de la sync — tout passe par [verrou]. Les publications au bus
 * sont ordonnées par ce même verrou (départ AVANT conclusion, compteurs
 * monotones) — le bus borné (8192, put bloquant) reste la discipline
 * d'écoulement établie par [ProgressBridge].
 */
internal class ConteurPhasesSync(
    private val projectDir: String,
    private val bus: EventBus,
) {
    private val verrou = Any()

    /** Phases ouvertes (départ annoncé) → instant d'ouverture. */
    private val ouvertes = LinkedHashMap<SyncPhase, Long>()

    /** Octets reçus cumulés des dépendances TERMINÉES. */
    private var octetsDependances = 0L

    /** Dernier élément de dépendance vu (sous-ligne « récents »). */
    private var dernierElementDependance: String? = null

    /** Configurations de projets terminées (compteur n). */
    private val configurationsTerminees = AtomicInteger()

    /** Ouvre une phase (départ) — idempotente : une phase déjà ouverte ne
     *  repart pas (la durée resterait mensongère). */
    fun ouvrir(
        phase: SyncPhase,
        element: String? = null,
    ) {
        synchronized(verrou) {
            if (ouvertes.containsKey(phase)) return
            ouvertes[phase] = System.currentTimeMillis()
            publier(phase, terminee = false, dureeMs = 0, Details(element = element))
        }
    }

    /** Progression d'une phase ouverte (octets, élément, compteur) — ouvre
     *  la phase au premier signe de vie (DEPENDANCES n'arrive que si des
     *  téléchargements ont LIEU : une sync hors ligne ne l'ouvre jamais). */
    fun progression(
        phase: SyncPhase,
        element: String? = null,
        octetsRecus: Long = 0,
        octetsTotal: Long? = null,
        compteur: Int? = null,
    ) {
        synchronized(verrou) {
            if (!ouvertes.containsKey(phase)) {
                ouvertes[phase] = System.currentTimeMillis()
                publier(phase, terminee = false, dureeMs = 0)
            }
            publier(
                phase,
                terminee = false,
                dureeMs = 0,
                Details(
                    element = element,
                    octetsRecus = octetsRecus,
                    octetsTotal = octetsTotal,
                    compteur = compteur,
                ),
            )
        }
    }

    /** Conclut une phase ouverte avec sa durée réelle — idempotente. */
    fun conclure(
        phase: SyncPhase,
        compteur: Int? = null,
    ) {
        synchronized(verrou) {
            val depart = ouvertes.remove(phase) ?: return
            publier(
                phase,
                terminee = true,
                dureeMs = System.currentTimeMillis() - depart,
                Details(compteur = compteur),
            )
        }
    }

    /** Un téléchargement de dépendance traverse (fin = octets comptés) :
     *  alimente la phase DEPENDANCES — cumul, élément récent, compteur n. */
    fun surTelechargement(detail: DetailTelechargement) {
        synchronized(verrou) {
            if (detail.termine) {
                octetsDependances += detail.octetsRecus
            }
            dernierElementDependance = detail.element
            progression(
                SyncPhase.DEPENDANCES,
                element = detail.element,
                octetsRecus = octetsDependances,
                compteur = detail.compteur,
            )
        }
    }

    /** Une configuration de projet traverse (départ ou fin) : alimente la
     *  phase CONFIGURATION — élément = projet, compteur n/N. */
    fun surConfiguration(
        element: String,
        terminee: Boolean,
        compteur: Int,
    ) {
        synchronized(verrou) {
            if (terminee) {
                // Fin d'un projet : mise à jour du compteur global.
                configurationsTerminees.set(compteur)
            }
            progression(
                SyncPhase.CONFIGURATION,
                element = element,
                compteur = if (terminee) configurationsTerminees.get() else compteur,
            )
        }
    }

    /** Conclut TOUTE phase restée ouverte (issue de la sync, quel que soit
     *  son sort) — jamais de ligne pendante dans la console du client. */
    fun conclureTout() {
        synchronized(verrou) {
            ouvertes.keys.toList().forEach(::conclure)
        }
    }

    /** Dernier élément de dépendance vu (sondeur de distribution). */
    fun elementDependanceCourant(): String? = dernierElementDependance

    /** Détails d'une progression de phase (transport vers [publier]). */
    private data class Details(
        val element: String? = null,
        val octetsRecus: Long = 0,
        val octetsTotal: Long? = null,
        val compteur: Int? = null,
    )

    private fun publier(
        phase: SyncPhase,
        terminee: Boolean,
        dureeMs: Long,
        details: Details = Details(),
    ) {
        bus.publier(
            SyncProgress(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projectDir,
                phase = phase,
                terminee = terminee,
                dureeMs = dureeMs,
                octetsRecus = details.octetsRecus,
                octetsTotal = details.octetsTotal,
                element = details.element,
                compteur = details.compteur,
            ),
        )
    }
}
