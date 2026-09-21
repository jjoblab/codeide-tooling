# build-logic

> Ce module est un **build inclus** (`includeBuild`) : il ne fait pas partie
> du graphe applicatif (section 5.1 du prompt maître).

Il porte la configuration partagée de tous les modules, source unique des
réglages (SDK, Kotlin, qualité, packaging) :

| Plugin | Rôle |
|---|---|
| `codeide.kotlin.library` | Bibliothèque Kotlin JVM pure (`core:model`, `core:domain`) — `explicitApi()`, avertissements en erreurs |
| `codeide.android.application` | Module `app` — identifiant `jo.codeide`, version lue dans `version.properties`, release minifiée |
| `codeide.android.library` | Bibliothèque Android (tous les `core:*` et `feature:*`) — Kotlin intégré AGP 9, Lint strict, tests Robolectric prêts |
| `codeide.android.feature` | Fonctionnalité — ajoute ViewBinding, Hilt et les dépendances d'interface autorisées (section 5.2) |
| `codeide.android.hilt` | Hilt via KSP (kapt est incompatible avec AGP 9) |
| `codeide.android.room` | Room via KSP, schémas exportés dans `<module>/schemas` |
| `codeide.module-rules` | Tâche `checkModuleDependencies` : vérification automatique des règles de dépendance, le build échoue en cas de violation |

Les versions des artefacts de plugins vivent dans
`build-logic/gradle/libs.versions.toml` ; celles des bibliothèques du projet,
dans `gradle/libs.versions.toml` (racine). La chaîne complète (AGP 9.4.1,
Kotlin intégré 2.2.10, Gradle 9.7.1) est justifiée dans l'ADR 0007.

## Dépendances

`com.android.tools.build:gradle`, `kotlin-gradle-plugin`,
`symbol-processing-gradle-plugin`, `hilt-android-gradle-plugin`,
`detekt-gradle-plugin`, `spotless-plugin-gradle`, `kover-gradle-plugin` —
toutes en `compileOnly` : les plugins réels sont déclarés `apply false` dans
le build racine pour garantir un chargeur de classes unique.
