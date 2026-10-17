# Politique de signature de CodeIDE (ADR 0067)

Deux régimes, deux niveaux de confidentialité — la distinction qui met fin
aux « conflits de package » entre APK successifs.

## Debug : identité publique, versionnée

`debug.keystore` est **commis dans le dépôt** et câblé comme signature de
la variante debug par le convention plugin `codeide.android.application`.

- Identifiants publics par convention Android (les mêmes que le
  `~/.android/debug.keystore` d'Android Studio) : magasin et clé
  `android`, alias `androiddebugkey`, sujet `CN=Android Debug,O=Android,C=US`.
- C'est le patron des clés de test d'AOSP (publiques par design) et de
  CodeAssist (tyron12233), qui versionne lui aussi son `debug.keystore`.
- **Pourquoi** : le runner GitHub n'embarque aucun
  `~/.android/debug.keystore` stable et les caches `actions/cache` sont
  scopés par ref (runs de tags lancés en parallèle) et évictables (7 jours
  d'inactivité) — sans clé commune, chaque APK CI portait une signature
  différente et Android refusait la mise à jour au-dessus de la précédente
  (« conflit de package », retours v0.35.2 puis v0.37.1). Versionnée, la
  clé fixe l'identité pour la CI, les contributeurs et toute machine
  locale : `v0.37.3` s'installe par-dessus `v0.37.2`, partout.
- Ce fichier n'est **pas** un secret : le perdre ne compromet rien (une
  clé debug ne prouve aucune identité) ; le régénérer changerait juste
  l'identité — à éviter sans bump de version majeure.
- L'intégrité est vérifiée à chaque build CI par
  `scripts/verify-signature.sh` (empreinte du certificat signataire ==
  empreinte du keystore versionné).

## Release : échelle hors dépôt

La clé de release ne vit **jamais** dans le dépôt (AGENTS.md règle 7).
Résolution par champ, le premier gagne :

1. `config/signature/keystore.properties` (gitignoré — modèle :
   `keystore.properties.example`) ;
2. propriété Gradle : `./gradlew assembleRelease -PRELEASE_STORE_FILE=…` ;
3. variable d'environnement : `RELEASE_STORE_FILE`, ….

Sans keystore résolu, la variante release reste non signée — comportement
inchangé par rapport à avant l'ADR 0067. Les quatre champs : `storeFile`
(résolu depuis la racine du dépôt), `storePassword`, `keyAlias`,
`keyPassword`.
