package jo.codeide.tooling.server

import java.io.File

/**
 * Instrumentation de transport Phase 1 (règle 10 du prompt : désactivable,
 * honnête, aucun changement de comportement visible quand elle est coupée).
 *
 * Elle comble les angles morts n°3 (la 2ᵉ file — [SocketClient.fileNormale]
 * — jamais rapportée par la ligne « file=N » de conclusion) et n°5
 * (composition du trafic : aucun comptage par type de message), et ajoute
 * les points de mesure T1, T1b, T2, T2b et T3b du plan §3.2 :
 *
 * - **T1** — temps d'attente du `put()` de l'[EventBus] (contre-pression
 *   ressentie par l'émetteur, fils de pompage de Gradle inclus) ;
 * - **T2** — durée poll → encodage → `envoyer` PAR événement côté
 *   consommateur, et comptage par type de message ;
 * - **T1b** — profondeur de la file d'écriture socket à l'enfilement et au
 *   défilement, âge de la frame la plus ancienne ;
 * - **T2b** — durée d'écriture par frame, nombre d'appels système par frame
 *   (via un flux compteur), temps CPU des fils serveur vs temps mur ;
 * - **T3b** — temps d'attente de file d'exécution (`schedstat`) des fils
 *   nommés du serveur, avant/après chaque build ;
 * - **métronome** — écarts entre l'horodatage embarqué d'un repère
 *   `[[CIDE-T …]]` émis DEPUIS le daemon Gradle et son arrivée à T0
 *   (retard AVANT T0, hypothèse H-T7).
 *
 * Tout sort dans le [Journal] (stderr → réinjection `gradle-server` dans le
 * journal applicatif de l'app) : les résumés restent dans l'export de logs
 * du propriétaire. Les accumulateurs sont remis à zéro à chaque build par
 * [demarrerBuild] et consommés par [conclureBuild].
 *
 * Singleton assumé : l'état est process-wide (un canal de mesure par
 * serveur ; les builds CONCURRENTS partagent les compteurs — cas de
 * diagnostic avancé, documenté dans docs/DIAGNOSTIC-LOGS.md). Les mutations
 * sont gardées par un verrou simple : les taux en jeu (≈ 60 lignes/build,
 * quelques milliers en déluge) ne créent aucune contention mesurable.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — l'API est
 * volontairement UN enregistrer() PAR point de mesure (T1, T2, T1b, T2b,
 * métronome) : un point = une fonction nommée, exactement le découpage du
 * plan §3.2 ; les fusionner en un enregistreur générique à drapeaux
 * perdrait la lisibilité du côté appelant (chaque site de mesure se lit
 * comme ce qu'il mesure).
 */
@Suppress("TooManyFunctions")
internal object InstrumentationTransport {
    /** Drapeau positionné au démarrage (`--instru-transport`, ServerConfig). */
    @Volatile
    var active: Boolean = false

    /** Verrou des accumulateurs du build courant (jamais sur le fil principal). */
    private val verrou = Any()

    // --- T1 : contre-pression de l'EventBus (attente du put bloquant) ---

    /** Nombre de publications observées. */
    private var publications = 0L

    /** Somme des attentes de `put()` (ms). */
    private var attentePublicationTotaleMs = 0L

    /** Attente maximale de `put()` (ms). */
    private var attentePublicationMaxMs = 0L

    // --- T2 : consommateur de l'EventBus (poll → encode → envoyer) ---

    /** Comptage et durées par type de message (nombre, somme, max en ms). */
    private val parType = LinkedHashMap<String, CompteurType>()

    // --- T1b : file d'écriture socket (profondeur, âge) ---

    /** Profondeur maximale de la file normale atteinte au défilement. */
    private var profondeurMaxFileEcriture = 0

    /** Profondeur maximale observée à l'enfilement (séparée du défilement). */
    private var profondeurMaxEnfilement = 0

    /** Âge maximal (ms) d'une frame au moment de son écriture. */
    private var ageMaxFrameMs = 0L

    // --- T2b : fil écrivain (durées d'écriture, appels système, CPU) ---

    /** Nombre de frames écrites par le fil écrivain. */
    private var framesEcrites = 0L

    /** Somme des durées d'écriture par frame (ms). */
    private var dureeEcritureTotaleMs = 0L

    /** Durée d'écriture maximale d'une frame (ms). */
    private var dureeEcritureMaxMs = 0L

    /** Nombre total d'appels `write` observés sur le flux de sortie. */
    private var appelsEcriture = 0L

    /** Instant de départ du build courant (ms mur). */
    private var departBuildMs = 0L

    /** Temps CPU (ms) du fil écrivain au départ du build (-1 : inconnu). */
    private var cpuEcrivainDepartMs = -1L

    /** Temps CPU (ms) du fil consommateur au départ (-1 : inconnu). */
    private var cpuConsommateurDepartMs = -1L

    /** Identifiant du fil écrivain (fourni par SocketClient), si vivant. */
    private var idFilEcrivain = -1L

    /** Identifiant du fil consommateur (fourni par EventBusSocket). */
    private var idFilConsommateur = -1L

    // --- T3b : schedstat (temps d'attente de file d'exécution) ---

    /** schedstat (run, attente, tranches) par nom de fil au départ du build. */
    private val schedstatDepart = LinkedHashMap<String, Triple<Long, Long, Long>>()

    // --- Métronome : repères [[CIDE-T seq epochMs]] ---

    /** Nombre de repères reçus. */
    private var reperes = 0L

    /** Somme des écarts epochMs → T0 (ms). */
    private var ecartReperesTotaleMs = 0L

    /** Écart maximal epochMs → T0 (ms). */
    private var ecartReperesMaxMs = 0L

    /** Écart minimal epochMs → T0 (ms) — négatif = horloges divergentes. */
    private var ecartReperesMinMs = Long.MAX_VALUE

    /** Dernier repère journalisé (échantillonnage : un par seconde). */
    private var dernierEchantillonSeq = -1L

    /**
     * Remet les accumulateurs à zéro pour [buildId] et prend les instantanés
     * de départ (CPU des fils, schedstat). Appelé par [BuildHandler.lancer].
     */
    fun demarrerBuild(buildId: String) {
        if (!active) return
        val schedstat = Schedstat.instantanes(tids())
        synchronized(verrou) {
            publications = 0
            attentePublicationTotaleMs = 0
            attentePublicationMaxMs = 0
            parType.clear()
            profondeurMaxFileEcriture = 0
            profondeurMaxEnfilement = 0
            ageMaxFrameMs = 0
            framesEcrites = 0
            dureeEcritureTotaleMs = 0
            dureeEcritureMaxMs = 0
            appelsEcriture = 0
            departBuildMs = System.currentTimeMillis()
            cpuEcrivainDepartMs = cpuFilMs(idFilEcrivain)
            cpuConsommateurDepartMs = cpuFilMs(idFilConsommateur)
            schedstatDepart.clear()
            schedstatDepart.putAll(schedstat)
            reinitialiserReperes()
        }
        Journal.info(
            "instru: build $buildId — instrumentation transport armee " +
                "(fils: ecrivain=$idFilEcrivain, consommateur=$idFilConsommateur, schedstat=${schedstat.size} fil(s))",
        )
    }

    /** T1 : une publication a attendu [attenteMs] dans le `put()` du bus. */
    fun enregistrerAttentePublication(attenteMs: Long) {
        if (!active) return
        synchronized(verrou) {
            publications++
            attentePublicationTotaleMs += attenteMs
            if (attenteMs > attentePublicationMaxMs) attentePublicationMaxMs = attenteMs
        }
    }

    /** T2 : un événement de type [type] a mis [dureeMs] (poll → envoyer). */
    fun enregistrerEvenement(
        type: String,
        dureeMs: Long,
    ) {
        if (!active) return
        synchronized(verrou) {
            parType.getOrPut(type) { CompteurType() }.enregistrer(dureeMs)
        }
    }

    /** T1b : la file d'écriture mesurait [profondeur] après l'opération. */
    fun enregistrerProfondeurFileEcriture(
        profondeur: Int,
        enfilement: Boolean,
    ) {
        if (!active) return
        synchronized(verrou) {
            if (profondeur > profondeurMaxFileEcriture) profondeurMaxFileEcriture = profondeur
            if (enfilement && profondeur > profondeurMaxEnfilement) profondeurMaxEnfilement = profondeur
        }
    }

    /** T1b : la frame écrite avait [ageMs] à son passage vers le socket. */
    fun enregistrerAgeFrame(ageMs: Long) {
        if (!active) return
        synchronized(verrou) {
            if (ageMs > ageMaxFrameMs) ageMaxFrameMs = ageMs
        }
    }

    /** T2b : une frame a été écrite en [dureeMs] avec [appels] appels système. */
    fun enregistrerEcritureFrame(
        dureeMs: Long,
        appels: Long,
    ) {
        if (!active) return
        synchronized(verrou) {
            framesEcrites++
            dureeEcritureTotaleMs += dureeMs
            if (dureeMs > dureeEcritureMaxMs) dureeEcritureMaxMs = dureeMs
            appelsEcriture += appels
        }
    }

    /** Métronome : un repère est arrivé à T0 avec [ecartMs] depuis son émission. */
    fun enregistrerRepere(
        ecartMs: Long,
        sequence: Long,
    ) {
        if (!active) return
        var echantillon = false
        synchronized(verrou) {
            reperes++
            ecartReperesTotaleMs += ecartMs
            if (ecartMs > ecartReperesMaxMs) ecartReperesMaxMs = ecartMs
            if (ecartMs < ecartReperesMinMs) ecartReperesMinMs = ecartMs
            // Échantillonnage du journal : un repère par seconde (période 250 ms).
            if (sequence - dernierEchantillonSeq >= ECART_ECHANTILLON_REPERES) {
                dernierEchantillonSeq = sequence
                echantillon = true
            }
        }
        if (echantillon) {
            Journal.info("instru: metronome seq=$sequence ecartT0=$ecartMs ms (daemon -> orchestrateur)")
        }
    }

    /** Mémorise les identifiants des fils mesurés (appelé au démarrage de ceux-ci). */
    fun brancherFils(
        idEcrivain: Long,
        idConsommateur: Long,
    ) {
        idFilEcrivain = idEcrivain
        idFilConsommateur = idConsommateur
    }

    /**
     * Consomme les accumulateurs et produit le résumé de conclusion (UNE
     * ligne, journalisée par [BuildHandler.publierFin] après la ligne
     * « build … conclu »). `null` quand l'instrumentation est inactive.
     */
    fun conclureBuild(buildId: String): String? {
        if (!active) return null
        val fin = System.currentTimeMillis()
        val schedstatFin = Schedstat.instantanes(tids())
        val cpuEcrivainFin = cpuFilMs(idFilEcrivain)
        val cpuConsommateurFin = cpuFilMs(idFilConsommateur)
        val resume: String
        synchronized(verrou) {
            val murMs = fin - departBuildMs
            val types =
                parType.entries.joinToString(", ") { (type, compteur) ->
                    "$type=${compteur.nombre}(moy ${compteur.moyenneMs()}ms, max ${compteur.maxMs}ms)"
                }
            val cpuEcrivain = deltaCpu(cpuEcrivainDepartMs, cpuEcrivainFin)
            val cpuConsommateur = deltaCpu(cpuConsommateurDepartMs, cpuConsommateurFin)
            val attentes = Schedstat.ecarts(schedstatDepart, schedstatFin)
            val reperesTxt =
                if (reperes > 0) {
                    val min = if (ecartReperesMinMs == Long.MAX_VALUE) 0 else ecartReperesMinMs
                    "$reperes reperes, ecart T0 min ${min}ms / " +
                        "moy ${ecartReperesTotaleMs / reperes}ms / max ${ecartReperesMaxMs}ms"
                } else {
                    "aucun repere recu (daemon muet ou sortie de configuration non relayee)"
                }
            // Fragments conditionnels extraits : la ligne de résumé reste
            // lisible et sous la limite ktlint de 120 caractères.
            val enfilement =
                if (profondeurMaxEnfilement > profondeurMaxFileEcriture) {
                    " (a l'enfilement: $profondeurMaxEnfilement)"
                } else {
                    ""
                }
            val dureeMoyenneEcriture = if (framesEcrites > 0) dureeEcritureTotaleMs / framesEcrites else 0
            val appelsParFrame = if (framesEcrites > 0) appelsEcriture / framesEcrites else 0
            resume =
                "instru: build $buildId — mur ${murMs}ms ; " +
                "T1 put bus: $publications pubs, attente max ${attentePublicationMaxMs}ms ; " +
                "T2 consommateur: ${types.ifEmpty { "rien" }} ; " +
                "T1b file ecriture: profondeur max $profondeurMaxFileEcriture$enfilement, " +
                "age max ${ageMaxFrameMs}ms ; " +
                "T2b ecrivain: $framesEcrites frames, " +
                "duree moy ${dureeMoyenneEcriture}ms / max ${dureeEcritureMaxMs}ms, " +
                "$appelsParFrame appels systeme/frame ; " +
                "CPU ecrivain $cpuEcrivain / consommateur $cpuConsommateur sur ${murMs}ms mur ; " +
                "T3b schedstat: ${attentes.ifEmpty { "indisponible" }} ; " +
                "metronome: $reperesTxt"
        }
        return resume
    }

    /** Remet à zéro les accumulateurs de repères (sous verrou). */
    private fun reinitialiserReperes() {
        reperes = 0
        ecartReperesTotaleMs = 0
        ecartReperesMaxMs = 0
        ecartReperesMinMs = Long.MAX_VALUE
        dernierEchantillonSeq = -1
    }

    /** Étiquettes → TID des fils surveillés. */
    private fun tids(): Map<String, Long> =
        mapOf(
            NOM_ECRIVAIN to idFilEcrivain,
            NOM_CONSOMMATEUR to idFilConsommateur,
        ).filterValues { it > 0 }

    /** Temps CPU (ms) du fil [tid], -1 si indisponible. */
    private fun cpuFilMs(tid: Long): Long {
        if (tid > 0) {
            val bean =
                java.lang.management.ManagementFactory
                    .getThreadMXBean()
            if (bean.isThreadCpuTimeSupported && bean.isThreadCpuTimeEnabled) {
                return runCatching { bean.getThreadCpuTime(tid) / NS_PAR_MS }.getOrDefault(-1L)
            }
        }
        return -1L
    }

    /** Delta CPU lisible (« inconnu » si un des deux bouts manque). */
    private fun deltaCpu(
        depart: Long,
        fin: Long,
    ): String =
        if (depart >= 0 &&
            fin >= 0
        ) {
            "${fin - depart}ms"
        } else {
            "inconnu"
        }

    /** Compteur nombre/somme/max d'une famille de messages (T2). */
    private class CompteurType {
        var nombre = 0L

        var totaleMs = 0L

        var maxMs = 0L

        fun enregistrer(dureeMs: Long) {
            nombre++
            totaleMs += dureeMs
            if (dureeMs > maxMs) maxMs = dureeMs
        }

        fun moyenneMs(): Long = if (nombre > 0) totaleMs / nombre else 0
    }

    /** Un repère métronome journalisé par seconde (période 250 ms → 4 repères). */
    private const val ECART_ECHANTILLON_REPERES = 4L

    /** Nanosecondes par milliseconde (conversion CPU). */
    private const val NS_PAR_MS = 1_000_000L

    /** Nom du fil écrivain du [SocketClient]. */
    private const val NOM_ECRIVAIN = "gradle-server-ecrivain"

    /** Nom du fil consommateur de l'[EventBusSocket]. */
    private const val NOM_CONSOMMATEUR = "gradle-server-evenements"
}

/**
 * Lecture de `/proc/self/task/<tid>/schedstat` (T3b) : temps passé sur CPU,
 * temps d'attente en file d'exécution et nombre de tranches, pour les fils
 * nommés du serveur — identifiés par TID (le noyau tronque `comm` à 15
 * caractères, ce qui rend les deux fils du transport indiscernables par
 * leur nom : « gradle-server-e… » pour les deux). Indisponible (noyau sans
 * `CONFIG_SCHEDSTATS`, restrictions SELinux) → carte vide, le résumé le dit
 * honnêtement.
 */
internal object Schedstat {
    /**
     * Instantané (run, attente, tranches — nanosecondes pour les deux
     * premiers) par étiquette de fil, pour les [tids] fournis.
     */
    fun instantanes(tids: Map<String, Long>): Map<String, Triple<Long, Long, Long>> =
        tids.entries
            .mapNotNull { (nom, tid) ->
                lireSchedstat(tid)?.let { schedstat -> nom to schedstat }
            }.toMap()

    /**
     * Lit le schedstat d'UN fil : `null` si le fichier est absent (noyau
     * sans CONFIG_SCHEDSTATS, restrictions) ou illisible.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — clauses de garde
     * typées (fichier absent, format court, champs non numériques), même
     * justification que `ParseurSyntheseBuild.analyserDureeMs`.
     */
    @Suppress("ReturnCount")
    private fun lireSchedstat(tid: Long): Triple<Long, Long, Long>? {
        val parties =
            runCatching { File("/proc/self/task/$tid/schedstat").readText().trim().split(" ") }
                .getOrNull() ?: return null
        if (parties.size < NOMBRE_CHAMPS_SCHEDSTAT) return null
        val run = parties[INDICE_RUN].toLongOrNull() ?: return null
        val attente = parties[INDICE_ATTENTE].toLongOrNull() ?: return null
        val tranches = parties[INDICE_TRANCHES].toLongOrNull() ?: return null
        return Triple(run, attente, tranches)
    }

    /** Écarts (avant → après) d'attente de file d'exécution, en ms lisibles. */
    fun ecarts(
        avant: Map<String, Triple<Long, Long, Long>>,
        apres: Map<String, Triple<Long, Long, Long>>,
    ): List<String> {
        val lignes = mutableListOf<String>()
        for ((nom, apresTriple) in apres) {
            val avantTriple = avant[nom] ?: continue
            val deltaAttenteMs = (apresTriple.second - avantTriple.second) / NS_PAR_MS
            val deltaTranches = apresTriple.third - avantTriple.third
            lignes.add("$nom +${deltaAttenteMs}ms d'attente sur $deltaTranches tranches")
        }
        return lignes
    }

    /** Nanosecondes par milliseconde (conversion schedstat). */
    private const val NS_PAR_MS = 1_000_000L

    /** Champs du fichier schedstat : run, attente, tranches. */
    private const val NOMBRE_CHAMPS_SCHEDSTAT = 3

    /** Indice du champ « temps sur CPU » (ns). */
    private const val INDICE_RUN = 0

    /** Indice du champ « attente de file d'exécution » (ns). */
    private const val INDICE_ATTENTE = 1

    /** Indice du champ « nombre de tranches ». */
    private const val INDICE_TRANCHES = 2
}
