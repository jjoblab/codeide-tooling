# Changelog — codeide-tooling

## 1.1.1 (2026-11-08)

Correctifs du retour d'appareil du 2026-11-07 (CodeIDE 0.206.0, moto g06
Android 15 / API 35) : l'orchestrateur mourait juste après le handshake —
« arrêt sur erreur : 'long java.lang.Thread.threadId()' » (NoSuchMethodError,
code de sortie 3, cinq relances épuisées, syncs interrompues). Le JAR 1.1.0
était bien déployé (le drapeau `--instru-transport` était accepté et annoncé
— le doute « ancien jar ? » du propriétaire était fondé à vérifier et est
écarté) ; la cause vivait dans le JAR lui-même.

### Trois défauts corrigés

- **`threadId()` est une API JDK 19** (fb7a8de) : le toolchain installé par
  l'app est openjdk-17 (ConfigurationBootstrap) — l'identifiant JVM des fils
  mesurés repasse par `Thread.getId()` (même valeur, contrat ThreadMXBean
  inchangé). La CI ne pouvait pas le voir : elle compilait et testait sur
  JDK 21, où l'API existe.
- **Interblocage lecture/écriture du JDK ≤ 20** (0e30649) : découvert en
  exécutant la suite sur JDK 17 APRÈS le correctif du crash —
  `Channels.newInputStream` tient `blockingLock()` pendant toute la lecture
  native et `Channels.newOutputStream` l'attend avant chaque écriture : le
  dispatcher (lecture bloquante) gelait le fil écrivain à sa première frame
  (« orchestrateur muet »). Reproduit dans un banc minimal (dump de piles à
  l'appui), invisible sur JDK 21 (flux réécrits). Correctif : `CanauxBloquants`,
  flux directs `SocketChannel.read/write` répliquant le comportement JDK 21.
  **Ce défaut était masqué par le crash précédent : les deux correctifs sont
  nécessaires ensemble.**
- **La sonde T3b (schedstat) était muette depuis la Phase 1** (05fd8dc) :
  elle cherchait `/proc/self/task/<id-jvm>/schedstat` — l'identifiant
  séquentiel JVM ne désigne rien sous /proc (vérifié : ids JVM 1, 19… contre
  tids noyau 10529…). Chaque fil mesuré s'auto-déclare désormais son TID
  noyau via `/proc/thread-self` (`FilNoyau`) ; le résumé de build porte enfin
  les écarts d'attente de file d'exécution.

### Observabilité et garde

- **Version d'artefact observable à l'ouverture** (af0a948) : le manifest du
  shadowJar porte `Implementation-Version` (alimenté par `version.properties`)
  et la ligne d'ouverture devient « orchestrateur 0.50.0 (artefact 1.1.1) —
  socket … ». Les JAR 1.0.0 et 1.1.0 ouvraient sur la même ligne : la question
  « est-ce le nouveau jar qui tourne ? » se lit désormais dans l'export de
  logs, sans inférence.
- **CI : matrice JDK 21 + 17** (e13055c) et **déclencheur `branches: [main]`
  réparé** — le filtre corrompu `ain]` (depuis f158eec) signifiait que la CI
  ne s'était jamais déclenchée sur les poussées de main.

### Vérification

Chaîne complète (spotless, detekt, règles de modules, tests, shadowJar)
verte sur JDK 21 ET JDK 17 — serveur : 18 classes, 137 tests, 0 échec sur
les deux jambes ; smoke test du JAR sur JDK 17 : ligne d'ouverture avec
artefact, sortie propre.

## 1.1.0 (2026-11-08)

Phase 1 (instrumentation du diagnostic « retard d'affichage ») et Phase 3
(audit de performance, référence Android Studio, plan d'améliorations),
fusionnées dans `main` le 2026-11-08. Première version publiée sur GitHub
Packages depuis la réécriture d'identité (la republication de 1.0.0 était
refusée en 409 — version déjà présente sur le registre).

### Phase 3 — audit de performance et plan « niveau Android Studio »

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

### Phase 1 — instrumentation du diagnostic « retard d'affichage »

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
