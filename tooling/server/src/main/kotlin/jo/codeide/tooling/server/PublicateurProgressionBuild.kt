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
}
