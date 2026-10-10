package jo.codeide.tooling.protocol

/**
 * Coordonnées de la bibliothèque applog-runtime (mission « Exécuter » R2,
 * ADR 0103) — LA source unique partagée entre le daemon (déploiement du
 * dépôt local) et le serveur (génération du script d'init).
 *
 * Miroir : la version vit aussi dans `applog-runtime/build.gradle.kts`
 * (tâche copierAarVersAssets) — incrémenter les DEUX ensemble (le contrôle
 * `controlerAarAssets` vérifie la présence du fichier, pas le numéro).
 */
public object ApplogCoordonnees {
    /** Groupe maven de la bibliothèque. */
    public const val GROUPE: String = "jo.codeide"

    /** Artefact maven de la bibliothèque. */
    public const val ARTEFACT: String = "applog-runtime"

    /** Version de la coordonnée expédiée. */
    public const val VERSION: String = "1.0.0"

    /** Coordonnée complète « groupe:artefact:version ». */
    public const val COORDONNEE: String = "$GROUPE:$ARTEFACT:$VERSION"

    /** Nom du fichier AAR dans les assets de l'app. */
    public const val NOM_AAR: String = "$ARTEFACT-$VERSION.aar"

    /** Nom du fichier POM dans les assets de l'app. */
    public const val NOM_POM: String = "$ARTEFACT-$VERSION.pom"

    /** Dossier des assets de l'app contenant AAR + POM. */
    public const val DOSSIER_ASSETS: String = "applog"

    /** Nom du script d'init Gradle généré (dépôt local injecté). */
    public const val NOM_SCRIPT_INIT: String = "init-applog.gradle.kts"
}
