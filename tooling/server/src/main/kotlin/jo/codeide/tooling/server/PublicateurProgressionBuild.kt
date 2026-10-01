package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol

/**
 * Publication de la progression d'un build sur l'[EventBus] (v0.45.1 —
 * extraite de [BuildHandler] quand les statuts textuels s'y sont ajoutés :
 * la classe pompait au-delà des limites de complexité). TOUT ce qu'un
 * build publie comme progression NON-ligne y passe — statuts textuels
 * (affichage immédiat côté client), écouteur téléchargements/
 * configuration, diagnostics stderr. Un seul objet, un seul souci :
 * transformer la progression du build en événements du protocole.
 */
internal class PublicateurProgressionBuild(
    private val bus: EventBus,
) {
    /**
     * Statut TEXTUEL du build (v0.45.1 — affichage immédiat) : sans détail
     * de téléchargement, le [ProgressEvent] voyage vers le client qui en
     * fait UNE LIGNE de console immédiate (tâches demandées, connexion au
     * daemon). Miroir serveur des « Executing tasks: » / « Starting Gradle
     * Daemon » de la console d'Android Studio — publiée au moment où elle
     * se produit, jamais différée.
     */
    fun statut(
        buildId: String,
        message: String,
    ) {
        bus.publier(
            jo.codeide.tooling.protocol.ProgressEvent(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                buildId = buildId,
                message = message,
            ),
        )
    }

    /**
     * Écouteur des téléchargements et de la configuration pour CE build
     * (v4, addendum §6) : chaque `FILE_DOWNLOAD` devient un
     * [jo.codeide.tooling.protocol.ProgressEvent] structuré —
     * « Téléchargement des dépendances n / N » se voit dans l'arbre du
     * build COMME dans celui de la sync. Compteurs remis à zéro par build.
     */
    fun ecouteur(buildId: String): EcouteurProgressionCommun =
        EcouteurProgressionCommun(
            surTelechargement = { detail ->
                bus.publier(
                    jo.codeide.tooling.protocol.ProgressEvent(
                        id = nouvelId(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        buildId = buildId,
                        message = "Téléchargement ${detail.element}",
                        telechargement = detail,
                    ),
                )
            },
            surConfiguration = { element, terminee, compteur ->
                bus.publier(
                    jo.codeide.tooling.protocol.ProgressEvent(
                        id = nouvelId(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        buildId = buildId,
                        message =
                            if (terminee) {
                                "Configuration $element — $compteur"
                            } else {
                                "Configuration $element…"
                            },
                    ),
                )
            },
        )

    /**
     * Publie le diagnostic extrait d'une ligne de stderr (G5) — une ligne
     * non reconnue (contexte, carets…) est simplement ignorée.
     */
    fun diagnostic(ligne: String) {
        ParseurDiagnostics.analyser(ligne)?.let { diagnostic ->
            bus.publier(diagnostic)
        }
    }

    /**
     * Conclusion de durées d'un build (v0.45.2 — décomposition honnête) :
     * la durée RAPPORTÉE PAR GRADLE (« BUILD SUCCESSFUL in 10s ») démarre
     * quand le build est planifié sur un daemon PRÊT — elle EXCLUT le
     * démarrage du daemon, qui peut prendre des minutes sur téléphone.
     * L'utilisateur voyait « in 10s » au bout de 200-300 s sans pouvoir
     * nommer l'écart : cette ligne le nomme — trois durées, zéro
     * arrière-pensée, jamais de chiffre inventé (sans durée Gradle
     * observée, le total seul reste honnête).
     */
    fun conclusionDurees(
        buildId: String,
        totalMs: Long,
        dureeGradleMs: Long?,
    ) {
        statut(buildId, decomposer(totalMs, dureeGradleMs))
    }

    /** « build Gradle : 10 s · démarrage du daemon : 235 s · total : 245 s ». */
    private fun decomposer(
        totalMs: Long,
        dureeGradleMs: Long?,
    ): String {
        val gradle = dureeGradleMs ?: return "total du build : ${formaterDuree(totalMs)}"
        val daemon = (totalMs - gradle).coerceAtLeast(0)
        return "build Gradle : ${formaterDuree(gradle)} · " +
            "démarrage du daemon : ${formaterDuree(daemon)} · " +
            "total : ${formaterDuree(totalMs)}"
    }

    /**
     * Millisecondes lisibles : « 800 ms », « 10 s », « 4 m 05 s ».
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — cascade de
     * formats mutuellement exclusifs (ms, secondes, minutes, heures),
     * chaque palier RETOURNE sa forme finale ; factoriser en table
     * déplacerait la lisibilité sans rien gagner.
     */
    @Suppress("ReturnCount")
    internal fun formaterDuree(ms: Long): String {
        if (ms < MS_PAR_SECONDE) return "$ms ms"
        val secondes = ms / MS_PAR_SECONDE
        if (secondes < SECONDES_PAR_MINUTE) return "$secondes s"
        val minutes = secondes / SECONDES_PAR_MINUTE
        val reste = secondes % SECONDES_PAR_MINUTE
        if (minutes < MINUTES_PAR_HEURE) return "$minutes m %02d s".format(reste)
        return "${minutes / MINUTES_PAR_HEURE} h %02d m %02d s".format(minutes % MINUTES_PAR_HEURE, reste)
    }

    private companion object {
        /** Millisecondes par seconde (définition de l'unité). */
        const val MS_PAR_SECONDE = 1_000L

        /** Secondes par minute (définition de l'unité). */
        const val SECONDES_PAR_MINUTE = 60L

        /** Minutes par heure (définition de l'unité). */
        const val MINUTES_PAR_HEURE = 60L
    }
}
