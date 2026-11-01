package jo.codeide.tooling.server

import java.io.File

/**
 * Coordonnées du métronome du daemon Gradle (Phase 1, §3.1c) : noms des
 * fichiers générés à côté du socket (répertoire privé de l'app, lu par le
 * daemon Gradle comme le script d'init applog).
 */
internal object CoordonneesMetronome {
    /** Nom du script d'init (`.gradle.kts` — Kotlin DSL, comme applog). */
    const val NOM_SCRIPT_INIT = "metronome-codeide.init.gradle.kts"

    /** Nom du fichier d'arrêt du fil métronome (créé à la conclusion du build). */
    const val NOM_FICHIER_ARRET = "metronome-codeide.arret"
}

/**
 * Repères du métronome (Phase 1, §3.1c) : une ligne `[[CIDE-T seq epochMs]]`
 * imprimée sur stdout DEPUIS le daemon Gradle toutes les 250 ms pendant le
 * build. Le préfixe `[[CIDE-T` est volontairement improbable dans une
 * sortie réelle ; les repères sont filtrés de l'affichage quand
 * l'instrumentation est active (ils ne deviennent JAMAIS des
 * [jo.codeide.tooling.protocol.BuildOutput]) et leur écart à T0 alimente
 * [InstrumentationTransport.enregistrerRepere].
 */
internal object ReperesMetronome {
    /** Préfixe exact d'un repère (crochets doublés : introuvables dans Gradle). */
    private const val PREFIXE = "[[CIDE-T "

    /** Motif `[[CIDE-T <seq> <epochMs>]]` — deux entiers, espaces exacts. */
    private val MOTIF = Regex("""\[\[CIDE-T (\d+) (\d+)]]""")

    /** `true` si [ligne] est un repère métronome. */
    fun estReper(ligne: String): Boolean = ligne.startsWith(PREFIXE) && MOTIF.matches(ligne.trim())

    /**
     * Analyse un repère : `null` si [ligne] n'en est pas un (retour rapide
     * sur le préfixe — le fil de pompage ne paie une regex que sur les
     * vrais repères).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — clauses de garde
     * typées (pas un repère, motif incomplet, entiers illisibles), même
     * justification que `ParseurSyntheseBuild.analyserDureeMs`.
     */
    @Suppress("ReturnCount")
    fun analyser(ligne: String): ReperesMetronomeLigne? {
        // Le trim est défensif DES DEUX côtés : le daemon peut imprimer la
        // ligne avec des espaces de bord, le préfixe se teste déjà trimé.
        if (!ligne.trim().startsWith(PREFIXE)) return null
        val groupes = MOTIF.find(ligne.trim())?.groupValues ?: return null
        val sequence = groupes[1].toLongOrNull() ?: return null
        val epochMs = groupes[2].toLongOrNull() ?: return null
        return ReperesMetronomeLigne(sequence, epochMs)
    }
}

/** Un repère décodé : numéro de séquence et horodatage d'émission (daemon). */
internal data class ReperesMetronomeLigne(
    val sequence: Long,
    val epochMs: Long,
)

/**
 * Générateur du script d'init du métronome (Phase 1, §3.1c) : même mécanisme
 * que l'injection applog ([GenerateurScriptAppLog] — script écrit par le
 * serveur, jamais versionné, passé par `-I`), mais de DIAGNOSTIC : il ne
 * touche ni dépôts ni dépendances, il imprime.
 *
 * Le fil s'arrête sur l'apparition du fichier d'arrêt (créé par
 * [BuildHandler] à la conclusion du build — le seul signal de fin fiable
 * entre les deux process sans toucher au protocole) ou sur un garde-fou de
 * 10 minutes (serveur mort, build zombie). Les lignes imprimées DEPUIS le
 * daemon pendant la session de build sont redirigées par le daemon vers la
 * sortie du build : elles voyagent donc par le MÊME chemin que les vraies
 * lignes, ce qui est précisément ce que le métronome mesure.
 */
internal object GenerateurScriptMetronome {
    /** Marqueur du fichier d'arrêt dans le modèle (sans dollar : raw string sûre). */
    private const val MARQUEUR_ARRET = "@@ARRET@@"

    /** Écrit le script vers [cible], qui signale [fichierArret] au fil métronome. */
    fun ecrire(
        cible: File,
        fichierArret: File,
    ) {
        cible.parentFile?.mkdirs()
        cible.writeText(texte(fichierArret))
    }

    /** Testabilité : le texte du script est exposé en lecture. */
    fun texte(fichierArret: File): String = MODELE.replace(MARQUEUR_ARRET, fichierArret.absolutePath)

    /**
     * Corps du script — Kotlin DSL, API publiques uniquement (aucun hook de
     * cycle de vie : le fil démarre à l'application du script, avant même
     * l'évaluation des réglages, et s'arrête par fichier ou garde-fou).
     */
    private const val MODELE =
        """
// Script d'init de DIAGNOSTIC — métronome du daemon Gradle (Phase 1, CodeIDE).
// Généré par l'orchestrateur (jamais versionné), passé par -I. Imprime sur
// stdout, toutes les 250 ms, un repère [[CIDE-T seq epochMs]] émis DEPUIS le
// daemon : l'écart entre cet instant et la réception côté orchestrateur
// mesure le retard AVANT T0 (daemon -> Tooling API, hypothèse H-T7).
// Arrêt : apparition du fichier ci-dessous (conclusion du build côté
// orchestrateur) ou garde-fou de 10 minutes. Aucun effet sur le build :
// impression seule, fil démon, exceptions avalées (diagnostic, pas
// infrastructure).

val arretMetronome = java.io.File("@@ARRET@@")
val departMetronome = System.currentTimeMillis()
val gardeFouMetronomeMs = 600000L
val periodeMetronomeMs = 250L
val filMetronome =
    Thread(
        {
            var sequence = 0L
            while (
                !arretMetronome.exists() &&
                System.currentTimeMillis() - departMetronome < gardeFouMetronomeMs
            ) {
                sequence += 1L
                println("[[CIDE-T ${'$'}sequence ${'$'}{System.currentTimeMillis()}]]")
                Thread.sleep(periodeMetronomeMs)
            }
        },
        "codeide-metronome",
    )
filMetronome.isDaemon = true
filMetronome.start()
        """
}
