# ADR — Le banc « socket seul » vit dans `:tooling:server`

> ADR 0001 du dépôt codeide-tooling — E5 du plan d'améliorations
> (dette documentaire fermée en 1.2.0). Décision initiale de la Phase 1
> (2026-11-01), consignée ici après coup : elle avait été prise dans le
> commit `f5aaeb5` sans ADR dédié.

## Contexte

Le banc « socket seul » (Phase 1, `DIAGNOSTIC-LOGS.md` §4.1) émet une
séquence `BuildStarted` → N `BuildOutput` → `BuildFinished` à travers
la VRAIE pile serveur — `EventBus` → `SocketClient` → `FrameCodec`,
deux files, fil écrivain unique — SANS Gradle. C'est l'isolateur du
coût par frame : X1 le tire pour répondre « transport ou pas transport »
sans qu'un build réel ne brouille la mesure.

Deux emplacements étaient possibles :

1. `:tooling:testing` — le module des fixtures partagées (projets Gradle
   réels, harnais d'intégration) ;
2. `:tooling:server` — le module de l'orchestrateur lui-même.

## Décision

**Le banc vit dans `:tooling:server`.**

Il a besoin des INTERNES de la pile : `EventBusSocket` (file bornée,
consommateur unique), `SocketClient` (files normale/prioritaire, fil
écrivain, instrumentation T1b/T2b), `InstrumentationTransport` — des
types `internal` qui NE SORTENT PAS du module serveur. Le mettre dans
`:tooling:testing` aurait exigé de les rendre `public` : élargir la
surface API d'un module pour un banc est le mauvais sens de la
dépendance.

Il partage le harnais de `ServeurIntegrationTest` (lancement du VRAI
`ServerMain` par classpath, socket réel, handshake réel) — même
discipline, même module.

## Conséquences

- `BancSocketSeul` est `internal` : invisible hors du serveur, testé par
  `BancSocketSeulTest` (ordre, intégralité, alternance stdout/stderr,
  `buildId` sur chaque ligne).
- Les PARAMÈTRES (`--banc-lignes`, `--banc-cadence-ms`,
  `--banc-longueur-ligne`, `--banc-contenu`) sont des arguments de la
  ligne de commande du process, PAS du protocole (règle 2 : aucun octet
  du fil ne change pour mesurer le fil).
- Le banc ne mesure RIEN lui-même : les chiffres viennent des lignes
  `instru:` (serveur) et `ConsoleLatence` (app) — une seule source de
  vérité de mesure, partagée avec les builds réels.
- `:tooling:testing` reste le home des FIXTURES (projets Gradle) — le
  « vrai build Android » du banc de charge reste
  `ServeurIntegrationTest` + fixtures.

## Références

- `DIAGNOSTIC-LOGS.md` §4.1 (banc), §6 (matrice X1–X10), §8 (verdict).
- `ServerConfig.ParametresBanc`, `ServerConfig.analyser`.
- ADR 0080 (fil écrivain unique) et ADR 0152 de CodeIDE
  (instrumentation client du transport).
