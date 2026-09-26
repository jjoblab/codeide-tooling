package jo.codeide.tooling.server

/**
 * Version de l'orchestrateur (§3.2 : `clientVersion` du `HelloRequest`).
 *
 * Indépendante de la version de l'app : le JAR déployé peut être plus ancien
 * que l'app qui l'héberge — c'est précisément le cas d'usage de la
 * négociation de version au handshake (§1.6). Alignée manuellement sur la
 * version de livraison de chaque étape (ADR 0040).
 */
public object ServerVersion {
    /** Version courante de l'orchestrateur (v0.34.0 : cohérence
     * Material You + Paramètres à deux niveaux — ADR 0059). */
    public const val CURRENT: String = "0.34.0"
}
