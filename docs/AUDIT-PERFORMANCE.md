# AUDIT-PERFORMANCE — audit du tooling « hors logs » et qualité du dépôt (Phase 3, §5.1–§5.2)

> **Statut : audit livré.** Chaque point porte son étiquette de preuve :
> **[MESURÉ BUREAU]** (commande + chiffre, bac à sable JVM 2 CPU, JDK 21,
> caches chauds — conditions détaillées par mesure), **[ANALYSE CODE]**
> (fichier:ligne, comportement déduit de la lecture, non mesuré) ou
> **[À MESURER APPAREIL]** (la mesure n'existe que sur le moto g06 de
> référence — interdit n°1 : aucun chiffre appareil n'est inventé ici).
> Mission : prompt « retard d'affichage des sorties de build, audit de
> performance, cohérence du tooling (niveau Android Studio) », §5.1–§5.2.
> Branche : `audit/performance-niveau-studio` (base : `mesure/instrumentation-logs`).

## 0. Conditions des mesures bureau

Bac à sable : 2 CPU, 4 Go de RAM partagés, JDK 21 (Temurin 21.0.13), Linux.
`GRADLE_USER_HOME` chaud (distribution et dépendances déjà téléchargées),
daemon Gradle de test chaud (7 minutes d'uptime au lancement de la suite) —
les chiffres « bureau » de ce document sont donc des mesures **à l'état
CHAUD**, utiles comme planchers et pour les ratios, jamais comme des
prédictions appareil (ARM, mémoire contrainte, flash lente). La matrice
appareil X1–X10 de `DIAGNOSTIC-LOGS.md` §6 reste la référence des verdicts.

## 1. Mesures bureau livrées (§5.1, points mesurables au bureau)

### 1.1 Démarrage du serveur [MESURÉ BUREAU]

`java -Xmx256m -jar gradle-server.jar --socket <tmp> --secret <s>` (le
shadowJar de `:tooling:server:shadowJar`, **8 081 829 octets = 7,7 Mio**),
chronométré du lancement du process à la première ligne du journal
(« orchestrateur 0.50.0 — socket … ») — 5 passages consécutifs :

| Run | 1 | 2 | 3 | 4 | 5 | **médiane** |
|---|---|---|---|---|---|---|
| Démarrage (ms) | 123 | 127 | 103 | 131 | 123 | **123** |

**Goulot : aucun.** 123 ms de JVM + chargement du jar de 7,7 Mio est dans
le bruit d'un lancement ; la fenêtre de connexion du daemon client est de
30 000 ms (`FENETRE_CONNEXION_MS`), le démarrage n'est PAS un facteur du
retard perçu. Le coût JAR (7,7 Mio à extraire des assets de l'APK puis à
copier) reste à mesurer appareil (installation/déploiement, pas latence).

### 1.2 Sync, builds et modèles sur fixtures [MESURÉ BUREAU]

Suite `:tooling:server:test` complète : **136 tests, 0 échec, 20,1 s
cumulées** (daemon Gradle et caches chauds). Durées des intégrations
réelles les plus significatives :

| Scénario (fixture) | Durée test |
|---|---|
| Sync « action unique » (minimal-java, daemon chaud) | **0,30 s** |
| Build minimal `assembleDebug` équivalent | 0,32 s |
| Build multi-modules | 0,28 s |
| Classpath Android modèle v2 (android-simple, AGP réel) | **5,76 s** |
| Dépendances résolues multi-modules | 3,81 s |
| Annulation d'un build long (60 s) | 0,78 s |
| Listage des tâches DEPUIS le cache après sync | 0,38 s (le test exige < 2 s) |
| Fermeture de projet (accusé + purge) | 2,72 s |

Lecture : à l'état chaud, la chaîne complète sync → listage → classpath
répond en quelques centaines de millisecondes — **les 194 s du journal de
terrain ne sont PAS reproductibles au bureau à l'état chaud** ; ils
s'expliquent par l'état FROID de l'appareil (distribution, daemon Gradle
froid, modèles AGP jamais résolus) et restent à décomposer par phases sur
appareil (X7, instrumentation INC-44 livrée côté app).

### 1.3 Coût de l'instrumentation [MESURÉ BUREAU, repris]

Banc socket seul JVM : 442 ms sans drapeau vs 463 ms avec, 20 000 lignes en
rafale (`DIAGNOSTIC-LOGS.md` §5) — **≈ +1 µs/ligne**, dans le bruit. À
re-mesurer appareil (X1 vs X1-sans-drapeau).

## 2. Audit par point (§5.1)

### 2.1 Temps de sync par phase — instrumentation LIVRÉE, verdict appareil

**[ANALYSE CODE]** `ConteurPhasesSync` (ouvrir/terminer par phase, durées
mesurées, octets de dépendances cumulés) publie des `SyncProgress` avec
`dureeMs` ; le client les affiche (fenêtre d'étapes) et — depuis la branche
miroir CodeIDE de cette mission (ADR 0152) — les **récapitule au journal à
chaque conclusion** (« sync réussie en X ms — phases : … »). La décomposition
de la sync de 194 s se lira donc DANS l'export de logs du propriétaire dès
la séance X7 : aucune mesure bureau ne la remplace (0,30 s chaud, phases
confondues — la décomposition n'a de sens que sur l'appareil).

### 2.2 Modèles (`ModelesAndroidV2`, `ModelesSync`) et action unique

**[ANALYSE CODE]** L'action unique v4 résout les modèles UNE fois par sync
(`SyncHandler`, `withCancellationToken` disponible — voir §2.8) et dépose
tout dans `CacheSync`. Le poste coûteux identifié au bureau est la
résolution du **modèle Android v2** : 5,76 s sur `android-simple` (AGP
réel, daemon chaud) — c'est LE candidat au gain sur les syncs répétées.
**Correctif proposé** : clé de cache d'empreinte côté serveur (la même
SHA-256 que le client utilise déjà pour `sync-state.json`) pour répondre
« inchangé » sans re-résoudre — chiffré dans `PLAN-AMELIORATIONS.md` §5.

### 2.3 « Empreinte identique — état sync restitué » puis sync de 194 s

**[ANALYSE CODE — la question posée par le prompt §5.1]**
`EditorViewModel.restaurerEtatSyncSiEmpreinteIdentique` (CodeIDE) :
l'empreinte SHA-256 des fichiers Gradle matche → l'état « Synchronisé »,
les tâches et le modèle LSP persisté sont restitués immédiatement
(`nourrirSessionLspDepuisModelePersiste`), PUIS une **revalidation
silencieuse** (`revaliderSyncSilencieusement`) relance une sync complète en
arrière-plan. La sync de 194 s du journal EST cette revalidation : elle
n'est PAS une restitution ratée (l'UI était déjà servie), c'est le
**réchauffage obligatoire du `CacheSync` serveur** — l'orchestrateur vient
de démarrer, son cache est vide, et la revalidation restaure tâches +
classpath + modèles pour les REQUÊTES suivantes (listage, classpath LSP).
**Goulot réel** : sur appareil à froid, cette revalidation paie la
distribution + le daemon Gradle + la configuration + les modèles AGP —
d'où 194 s. **Correctifs proposés** (ordonnés) :
1. **Empreinte serveur persistée** : si l'empreinte du projet matche
   l'entrée de `CacheSync` persistée au disque (orchestrateur redémarré),
   la revalidation devient un no-op et non une sync complète — gain
   potentiel : la quasi-totalité des 194 s dans le cas « rien n'a changé » ;
2. à défaut, **différer** la revalidation derrière la première
   interaction qui en a besoin (listage, classpath) — la sync d'ouverture
   disparaît du chemin critique.
Les deux sont chiffrés dans `PLAN-AMELIORATIONS.md`.

### 2.4 Démarrage et réutilisation du daemon Gradle

**[ANALYSE CODE + MESURÉ BUREAU]** `GradleConnectorPool` réutilise UNE
`ProjectConnection` par chemin canonique de projet ; le daemon Gradle
sous-jacent vit tant que Gradle le garde (délai d'inactivité par défaut de
3 h). Bureau : les 2e+ syncs d'une même session de test répondent en 0,30 s
(daemon chaud) quand un démarrage à froid coûte la distribution + JVM.
**[À MESURER APPAREIL]** X-pont : durée d'une sync à froid (premier build
après installation) vs chaud (2e build) — l'écart EST le coût du daemon,
à rapporter dans le verdict.

### 2.5 Options JVM et propriétés Gradle

**[ANALYSE CODE]** Le serveur pose `--console=plain` (`CONSOLE_TEXTE`,
`SyncHandler.kt:235,294`) et laisse passer les arguments de l'utilisateur
(attention INC-31 : ils s'appliquent aussi à la sync et au classpath LSP).
Il ne pose NI `--parallel`, NI configuration cache, NI build cache — la
fixture les borne (`org.gradle.jvmargs=-Xmx512m` dans les fixtures,
ADR 0040) mais le serveur n'impose rien. **Proposition** (T-F10, après
verdict uniquement — règle « mesure d'abord ») : `--parallel` pour les
multi-modules, `org.gradle.caching=true`, et l'étude du configuration
cache côté Tooling API. Chacun : mesure avant/après sur les bancs §3.1.

### 2.6 Coût de `ResolvedDependenciesHandler`

**[MESURÉ BUREAU]** 3,81 s sur `multi-module` (résolution `IdeaProject`
par module, dépendances par entrée). **Goulot** : la traversée complète de
l'arbre à CHAQUE requête — aucun cache d'empreinte. **Correctif proposé** :
répondre depuis `CacheSync` quand l'empreinte du projet n'a pas changé
(même mécanisme que §2.3), gain attendu ≈ le coût complet sur les requêtes
répétées.

### 2.7 Latence d'annulation de bout en bout

**[MESURÉ BUREAU]** Le test d'annulation coupe un build de 60 000 ms en
0,78 s (détection → arrêt → conclusion ANNULE). **[À MESURER APPAREIL]**
la latence VISIBLE (bouton → arrêt du rendu console) — cible indicative du
prompt : < 500 ms. Côté sync (INC-12) : voir §2.8.

### 2.8 Annulation de la SYNC (INC-12) — le risque baisse

**[CONFIRMÉ PAR LA DOCUMENTATION]** Le chiffrage initial de
`PLAN-AMELIORATIONS.md` §1 posait un risque « MOYEN — dépend de l'API
réelle : la sync est un `ModelBuilder`, l'API de cancellation n'y est PAS
symétrique du build ». La lecture de la javadoc Gradle (API 9.8,
`ConfigurableLauncher`) **infirme ce doute d'architecture** :
`ModelBuilder` étend `ConfigurableLauncher`, qui expose
`withCancellationToken(CancellationToken)` — la mécanique d'annulation est
LA MÊME pour les builds (`BuildLauncher`), les tests (`TestLauncher`) et
les modèles. Source : docs.gradle.org, `org.gradle.tooling.ConfigurableLauncher`.
**Reste à vérifier à l'implémentation** (lecture ciblée avant engagement) :
le comportement du daemon Gradle après l'annulation d'un `ModelBuilder` en
cours de configuration (état de la connexion, caches partiels), et la
symétrie du résultat côté protocole (`SyncCancelled` vs échec). Le risque
passe de MOYEN à FAIBLE, le chiffrage ~2-3 j-h est maintenu.

### 2.9 Empreinte mémoire cumulée

**[ANALYSE CODE + À MESURER APPAREIL]** Orchestrateur : tas borné
`-Xmx256m` (ADR 0062, suffixe d'unité obligatoire), exposé au client par
`HeapMonitor` → `HeapEvent` (affiché dans l'écran de configuration).
Daemon Gradle : borné par les fixtures à 512 Mio en test ; sur appareil,
la taille réelle dépend du projet. LSP + app : hors périmètre de ce dépôt,
mais le signal de pression mémoire est désormais CONSOLIDÉ côté app
(ADR 0152 : `PressionMemoire` avec availMem + MemAvailable/Cached).
**[À MESURER APPAREIL]** X4 : profil mémoire pendant la séance (le fait F6
— signal de pression reçu — devient exploitable).

### 2.10 Fuites et robustesse

**[ANALYSE CODE + MESURÉ BUREAU]** Nettoyage : `CacheSync.purger`
(fermeture PAR PROJET, v9), canaux et états par build fermés à la
conclusion (`pomperFin`), cartes vidées à la fermeture de session
(`fermerSession` côté client — la branche miroir y vide aussi les
accumulateurs de sync INC-44). Robustesse éprouvée par la suite existante :
`ChaosToolingTest` (déconnexions, process mort, pong), `BoutEnBoutTest`
(bout-en-bout JVM réel), `ServeurIntegrationTest` (15 intégrations réelles,
136 tests verts). **Aucune fuite identifiée en lecture** ; la durée de vie
des sessions (files, connexions du pool) est bornée par la fermeture de
session — à surveiller sur les syncs très longues appareil.

## 3. Qualité du dépôt (§5.2)

### 3.1 Revue de code

- **Concurrence** : fils nommés (`gradle-server-evenements`,
  `gradle-server-ecrivain`, `tooling-lecteur`), files bornées à `put()`
  bloquant (contre-pression, jamais `DROP_OLDEST` côté serveur — règle 3),
  écrivain unique (ADR 0080), lecture par fil dédié (ADR 0081). Les
  concessions sont documentées (T3b : `comm` tronqué à 15 caractères).
- **Gestion d'erreurs** : codes typés du protocole (`ErrorCode`), échecs
  transportés par `ErrorResponse`/`ResultHandler`, aucune exception avalée
  en silence (detekt `SwallowedException` vert, exemptions commentées).
- **API publique** : `explicitApi()` forcé par les conventions
  `codeide.kotlin.library` / `codeide.tooling.server` (build-logic).
- **Sécurité** : secret de handshake par lancement, socket sous répertoire
  privé `0700`, secret jamais journalisé (`ServerConfig` KDoc).

### 3.2 Couverture de tests et tests de charge

`:tooling:server:test` : 136 tests verts (dont les 15 intégrations réelles
et le banc socket seul). Les autres modules (protocol, api, testing) sont
couverts par la chaîne du README — le décompte complet est dans le rapport
de session. Tests de charge : le banc déluge (`--banc-lignes` jusqu'à
200 000, contenus ansi/utf8/multi) EST le harnais de charge, activable
appareil depuis la configuration (ADR 0152). **Manque identifié** : aucun
seuil kover sur ce dépôt (contrairement à CodeIDE) — proposition dans le
plan (vague 3).

### 3.3 Compatibilité de protocole

`PROTOCOL_VERSION` = 10, `ProtocoleRoundTripTest` + échantillons dorés
(`src/test/resources/golden/`, 33 fichiers) verrouillent le format ;
l'instrumentation livrée en Phase 1 n'a changé **aucun octet du fil**
(règle 2 confirmée par la matrice : le JAR et le client se déploient
séparément). La matrice ancien/nouveau reste couverte par la négociation de
handshake (test dédié).

### 3.4 Publication

`publish.yml` : les 4 modules vers GitHub Packages au tag `v*`
(`GITHUB_TOKEN` packages:write), versionnement indépendant
(`version.properties`, tags `v1.x`), CHANGELOG tenu. [ANALYSE CODE] Le
workflow est cohérent avec la chaîne CI (`ci.yml`, JVM pur).

### 3.5 Dette technique priorisée

1. **Journal de sync serveur absent** (T-F9, Phase 2) : le serveur ne
   journalise que ses échecs — la conclusion « sync … conclu : N ms » et
   les phases au `Journal` manquent côté serveur (le client les a depuis
   l'INC-44).
2. **Seuils kover absents** sur ce dépôt (voir §3.2).
3. **`schedstat` T3b tronqué** (`comm` à 15 caractères) : les deux fils du
   transport y sont indiscernables — documenté, contournable par l'ordre.
4. **ADR d'infrastructure manquants** : la décision « banc dans
   `:tooling:server` (accès aux internes) plutôt que `:tooling:testing` »
   (DIAGNOSTIC-LOGS §4.1) mérite un ADR formel quand la Phase 2 l'étendra.
5. **Suppression detekt ciblée `LongMethod`** ajoutée à
   `SettingsDataStore` côté CodeIDE (31 réglages) — à surveiller si la
   projection grossit encore.

## 4. Historique

- 2026-11-08 : création (Phase 3). Mesures bureau : démarrage serveur
  (médiane 123 ms, 5 runs), suite serveur 136 tests verts / 20,1 s, durées
  par intégration. Analyse du paradoxe « empreinte identique puis 194 s »
  (revalidation silencieuse). Abaissement du risque INC-12 (javadoc
  `ConfigurableLauncher`).
