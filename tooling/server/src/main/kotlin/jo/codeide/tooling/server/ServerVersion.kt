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
    /** Version courante de l'orchestrateur (v0.50.0 : alignée sur la
     * livraison du fil de lecture dédié côté app — ADR 0081, l'orchestrateur
     * lui-même est inchangé (fil écrivain v0.49.0, ADR 0080 ; v0.35.4 :
     * conteneur vertical unique dans les cartes des sections des Paramètres —
     * ADR 0064 ; v0.35.3 : tolérance au démontage du tuyau + garde-fou de
     * supervision — ADR 0063). */
    public const val CURRENT: String = "0.50.0"

    /**
     * Version d'ARTEFACT du JAR en cours d'exécution (attribut
     * `Implementation-Version` du manifest, alimenté par `version.properties`
     * à la construction) — `null` en exécution de test (classpath, sans
     * manifest).
     *
     * C'est ELLE qui distingue deux livraisons du MÊME niveau de protocole :
     * « orchestrateur 0.50.0 » seul ne dit pas quel JAR tourne — constat du
     * retour d'appareil du 2026-11-07 (les JAR 1.0.0 et 1.1.0
     * journalisaient la même ligne d'ouverture, seul le drapeau
     * `--instru-transport` accepté permettait d'inférer la bonne livraison).
     */
    public fun artefact(): String? = ServerVersion::class.java.`package`?.implementationVersion
}
