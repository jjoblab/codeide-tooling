# REFERENCE-ANDROID-STUDIO — comment Android Studio le fait, et ce qui est portable ici (Phase 3, §5.3)

> **Statut : fiches de référence (lecture seule).** Sources : documentation
> publique d'Android Studio (developer.android.com), SDK de la plateforme
> IntelliJ (plugins.jetbrains.com/docs) et javadoc de la Tooling API Gradle
> (docs.gradle.org) — aucune ingénierie inverse, aucun projet tiers hors
> liste autorisée (règle 7 : Android Studio / IntelliJ Platform / Gradle
> Tooling API / AGP uniquement). Pour chaque fonction : ce qu'Android
> Studio fait, ce que CodeIDE a déjà, ce qui est portable (coût, priorité).
> Les coûts sont des **estimations de mise en œuvre** à valider ; les
> cibles chiffrées sont des **propositions** (point de départ du prompt
> §5.3), jamais des faits mesurés ici.

## Fiche 1 — La fenêtre Build (sorties)

**Android Studio** (developer.android.com/studio/run) :
- La fenêtre Build a un onglet **Sync** et un onglet **Build Output**,
  conservés SIMULTANÉMENT (les deux historiques restent consultables) ;
- **Build Output affiche les tâches en ARBRE** : chaque nœud est une phase
  de build ou un groupe de tâches ; en cas d'erreur, l'inspection d'un
  nœud montre le message ; l'onglet Sync affiche les tâches de
  synchronisation et un **résumé de l'impact des téléchargements** ;
- **Filtres** : masquer les avertissements, les tâches réussies, ou les
  deux — pour ne voir que les problèmes ;
- Bouton **Restart** (rejoue la dernière action de build) ;
- **Options de ligne de commande** persistées (Settings > Build, Execution,
  Deployment > Compiler > Command-line Options) appliquées au prochain
  build — le mécanisme exact de « réglages du compilateur » ;
- Un onglet **Build Analyzer** complète la fenêtre (fiche 3).

**CodeIDE aujourd'hui** : sélecteur segmenté Sync | Build avec les DEUX
consoles accumulées en parallèle (v0.46.0, INC-20 volet C) ✓ ; flux brut
reconstitué comme la fenêtre Build (ADR 0078) ✓ ; « Réessayer » après
échec (INC-21) ✓ ; diagnostics → « Voir les problèmes » et goto fichier
(ADR 0147) ✓.

**Portable ici** (par coût croissant) :
1. **Filtres de console** (avertissements / tâches réussies) — faible :
   le style des lignes existe déjà (`StyleLigne`), un filtre en aval du
   `SharedFlow` suffit ;
2. **Liens fichier:ligne cliquables dans la console** — moyen : le parseur
   de diagnostics existe côté serveur, la confection de spans cliquables
   est le même mécanisme que l'éditeur (à faire APRÈS la virtualisation,
   fiche 5 du plan) ;
3. **Arbre des tâches repliable** — élevé : exige une vue structurée par
   événements `TaskStarted/TaskFinished` (contrat « l'information vit dans
   le flux OU dans l'en-tête » de l'ADR 0078 à amender explicitement) ;
   priorité BASSE tant que le flux brut honore la parité.

## Fiche 2 — La synchronisation Gradle

**Android Studio** : la sync apparaît dans l'onglet dédié avec ses tâches ;
elle est **annulable** (bouton Stop pendant la sync) ; la **sync
incrémentale** ne reconfigure que ce qui a changé ; l'état « Sync » de la
barre d'état reflète la progression.

**Gradle Tooling API** (docs.gradle.org, `org.gradle.tooling`) :
`ModelBuilder` (les modèles = la sync) étend `ConfigurableLauncher`, qui
expose **`withCancellationToken`** — l'annulation est disponible pour les
modèles exactement comme pour les builds ; les écouteurs de progression
s'abonnent PAR TYPE d'opération (`OperationType`), y compris
`PROJECT_CONFIGURATION` — le mécanisme des phases de `SyncHandler` est la
bonne voie.

**CodeIDE aujourd'hui** : phases réelles annoncées et affichées (v4),
téléchargements dans l'en-tête du panneau, délai d'inactivité réarmé par
signe de vie, honnêteté « Sync non annulable » (volet C, INC-12) ;
récapitulatif de phases et latences au journal (INC-44, ADR 0152).

**Portable ici** :
1. **Annulation de la sync** (INC-12) — le risque d'architecture est
   levé par la javadoc (`withCancellationToken` sur `ModelBuilder`,
   cf. AUDIT-PERFORMANCE §2.8) : chiffrage ~2-3 j-h, ARBITRAGE
   PROPRIÉTAIRE demandé (interdit n°5 : aucune fonction nouvelle sans
   accord) ;
2. **Sync incrémentale** — élevé et dépendant de Gradle (la Tooling API
   reconfigure tout le projet au `ModelBuilder`) : à traiter par le
   contour d'empreinte (AUDIT §2.3) plutôt que par une vraie sync
   incrémentale.

## Fiche 3 — Le Build Analyzer

**Android Studio** (developer.android.com/build/build-analyzer) : rapport
généré **automatiquement à chaque build** — durée totale, **plugins
classés par temps de tâches**, **tâches les plus responsables de la
durée** (groupées par catégorie avec AGP 8+), **avertissements**
(configuration cache non activé, processeurs d'annotations non
incrémentaux, tâches « always run », Jetifier…), et un **résumé du temps
de téléchargement des dépendances par dépôt**.

**CodeIDE aujourd'hui** : les prémices existent côté transport — durées
par tâche (`TaskFinished.durationMs`), comptage par type de message
(instrumentation T2), octets de téléchargements cumulés
(`ConteurPhasesSync`), récapitulatif de phases au journal (INC-44).

**Portable ici** : un écran **« Analyseur de build » minimal** — les N
tâches les plus longues du dernier build + la répartition des phases de
sync + le volume téléchargé — est un pur assemblage d'états DÉJÀ
transportés (coût moyen, priorité moyenne, après le verdict : les chiffres
appareil diront si les tâches lentes y dominent). Les avertissements de
configuration (cache, processeurs) exigent des données que le serveur
n'a pas encore : non proposé.

## Fiche 4 — Progression et indicateurs d'état

**Android Studio** : progression dans la barre d'état pendant les builds
et syncs, indicateur d'activité de build, fenêtre Build actualisée en
continu ; les options « afficher la sortie en continu » relancent le flux.

**CodeIDE aujourd'hui** : en-tête tooling toujours présent avec indicateur
compact (INC-10), flash de verdict (INC-13), rendu dédoublonné et chrono
(INC-14), statuts localisés (INC-15/16) — volet C livré, aligné sur la
référence ; la barre d'état n'existe pas sur mobile (le pendant est la
notification + l'en-tête du panneau : couverts par `DecisionNotificationTooling`).

**Portable ici** : rien d'immédiat — la parité est atteinte sur ce point ;
la marge est dans la fluidité sous déluge (H-V1…V5, Phase 2 après
verdict).

## Cibles de vitesse proposées (à valider par le propriétaire)

Point de départ indicatif du prompt §5.3, repris TEL QUEL comme cibles de
travail — à confronter aux captures Android Studio
(`PROTOCOLE-CAPTURES-ANDROID-STUDIO.md`) et aux mesures appareil :

| Cible | Valeur proposée |
|---|---|
| Première ligne affichée après son émission | < 200 ms |
| Latence de transport p95 (banc socket seul, 20 lignes/s) | < 250 ms |
| Ligne affichée après le `BuildFinished` | AUCUNE au-delà de 1 s |
| Défilement sous déluge | 60 images/s |
| Gel du fil principal | Aucun > 100 ms |
| Annulation visible | < 500 ms |

## Sources

- Fenêtre Build, onglets, filtres, Restart, options de compilateur :
  developer.android.com/studio/run (consulté le 2026-11-08).
- Build Analyzer : developer.android.com/build/build-analyzer (consulté le
  2026-11-08).
- Optimisation des builds : developer.android.com/build/optimize-your-build,
  developer.android.com/build/profile-your-build.
- SDK plateforme IntelliJ, API d'exécution (`ProcessHandler`,
  `ExecutionConsole`, contenu des fenêtres d'outils) :
  plugins.jetbrains.com/docs/intellij/execution.html.
- Tooling API Gradle (cancellation, progression par type, lanceurs) :
  docs.gradle.org — javadoc `org.gradle.tooling.ConfigurableLauncher`,
  `BuildLauncher`, `ModelBuilder`.

## Historique

- 2026-11-08 : création (Phase 3).
