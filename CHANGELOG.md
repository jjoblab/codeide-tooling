# Changelog — codeide-tooling

## [Non publié] — branche `audit/performance-niveau-studio` (Phase 3 : audit de performance et plan « niveau Android Studio »)

- **`docs/AUDIT-PERFORMANCE.md`** (§5.1–§5.2) : mesures bureau réelles
  (démarrage serveur médiane **123 ms** sur 5 runs, shadowJar 7,7 Mio ;
  suite serveur 136 tests verts / 20,1 s ; sync chaude 0,30 s, classpath
  Android v2 5,76 s, dépendances 3,81 s, annulation 0,78 s), analyse du
  paradoxe « empreinte identique puis sync de 194 s » (la revalidation
  silencieuse réchauffe le `CacheSync` serveur), abaissement du risque
  INC-12 (javadoc `ConfigurableLauncher` : `withCancellationToken` existe
  sur `ModelBuilder`), revue de qualité (concurrence, erreurs,
  `explicitApi`, protocole, publication) et dette priorisée.
- **`docs/REFERENCE-ANDROID-STUDIO.md`** (§5.3) : fiches « comment Android
  Studio le fait » (fenêtre Build, sync, Build Analyzer, indicateurs)
  depuis les documentations publiques, avec ce qui est portable (coût,
  priorité) et les **cibles proposées** (< 200 ms première ligne, p95
  transport < 250 ms, aucune ligne > 1 s après `BuildFinished`, 60 i/s,
  gel < 100 ms, annulation < 500 ms — à valider).
- **`docs/PROTOCOLE-CAPTURES-ANDROID-STUDIO.md`** (§5.3) : protocole de
  build témoin chronométré DANS Android Studio (30–40 min) pour caler les
  cibles sur des chiffres réels du propriétaire.
- **`docs/PLAN-AMELIORATIONS.md` étendu** (§5.4) : écarts E1–E9 priorisés
  impact × effort × risque (empreinte serveur persistée en tête — la
  réouverture d'un projet inchangé cesse de re-payer la sync), vagues 0–3
  avec critères de sortie mesurables, séparation nette des
  responsabilités (tooling / CodeIDE / propriétaire).

## [Non publié] — branche `mesure/instrumentation-logs` (Phase 1, diagnostic du retard d'affichage)

- **Instrumentation de transport** (drapeau `--instru-transport`, règle 10 :
  désactivable, coût mesuré ≈ +1 µs/ligne sur banc JVM) : T1 (attente du
  `put()` du bus), T2 (durée poll→encode→envoyer PAR événement + comptage
  PAR TYPE de message — angle mort n°5), T1b (profondeur et **âge** de la
  file d'écriture socket — angle mort n°3), T2b (durée d'écriture, nombre
  d'appels système par frame via flux compteur, CPU des fils vs mur),
  T3b (`schedstat` par TID avant/après build). Résumé `instru:` journalisé
  à chaque conclusion de build.
- **Métronome du daemon Gradle** (§3.1c) : script d'init
  `metronome-codeide.init.gradle.kts` passé par `-I` sous le drapeau —
  repères `[[CIDE-T seq epochMs]]` toutes les 250 ms DEPUIS le daemon,
  filtrés de l'affichage, écarts à T0 consignés (H-T7 : retard avant T0).
  Arrêt par fichier (conclusion du build) ou garde-fou 10 min.
- **Banc « socket seul »** (§3.1b) : `--banc-socket-seul` +
  `--banc-lignes/--banc-cadence-ms/--banc-longueur-ligne/--banc-contenu` —
  cycle `BuildStarted` → N `BuildOutput` → `BuildFinished` à travers la
  VRAIE pile serveur sans Gradle (déluge §3.1a inclus). Vérifié par
  `BancSocketSeulTest` (ordre, intégralité, alternance des flux).
- **Conclusion de build corrigée** : `file=` rapporte les DEUX files
  (EventBus + écriture socket) avec profondeur maximale et âge maximal ;
  le seuil d'alerte 512 devient relatif et temporel (profondeur ≥ 32 OU
  âge ≥ 1 s).
- **docs/DIAGNOSTIC-LOGS.md** : méthode, bancs, règle de décision (§3.4
  verbatim), matrice X1–X10, coût mesuré, verdict EN ATTENTE des mesures
  appareil (STOP §3.5 respecté — aucun chiffre inventé).
- Octets du fil INCHANGÉS (règle 2) : framing, messages et
  `PROTOCOL_VERSION` intacts ; drapeau coupé = comportement identique à
  1.0.0 (seuls les âges/profondeurs maxima des files restent suivis).

## 1.0.0 (2026-10-17)

- Extraction de jjoblab/CodeIDE par `git filter-repo` (historique
  conservé : 315 commits touchant `tooling/`, `build-logic/`, `gradle/`
  et les fichiers de build racine).
- Périmètre : `:tooling:protocol`, `:tooling:api`, `:tooling:server`,
  `:tooling:testing`. `tooling:client` et `tooling:daemon` restent dans
  CodeIDE (dépendance `:core:domain` non encore publiée) — leur
  historique est conservé ici.
- Adaptations : `settings.gradle.kts` tooling-only (retrait du bloc
  LayoutLib composite et des dépôts app — JitPack, cache-redirector
  JetBrains), version indépendante 1.0.0, config detekt partagée
  importée, `config/signature` retirée (dépôt JVM pur).
- Vérifié : spotless + detekt + checkModuleDependencies verts, 146
  tests JVM verts, `gradle-server.jar` (7,7 Mo) construit.
