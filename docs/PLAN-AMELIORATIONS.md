# PLAN-AMELIORATIONS — tooling « niveau Android Studio » (Phase 3, §5.4)

> **Statut : plan complet d'arbitrage du propriétaire.** Étendu par la
> Phase 3 (audit `AUDIT-PERFORMANCE.md`, référence
> `REFERENCE-ANDROID-STUDIO.md`). Le volet A reste **STOPPÉ au verdict**
> (`DIAGNOSTIC-LOGS.md` §8 : les mesures appareil restent à exécuter par
> le propriétaire) — les correctifs de transport (T-F1 à T-F10) ne
> s'appliqueront qu'APRÈS ce verdict, dans l'ordre de la preuve. Les
> fonctions marquées **[ARBITRAGE]** ne s'implémentent pas sans accord
> (interdit n°5) ; les chantiers marqués **[APRÈS VERDICT]** attendent les
> chiffres de la matrice X1–X10.

## 1. Annulation de la synchronisation (INC-12 — [ARBITRAGE], risque abaissé)

**Vécu** : sync de 194 s sans pouvoir arrêter ; garde serveur de 5 min ;
aucun libellé ne l'explique. L'honnêteté est livrée côté CodeIDE
(« Sync non annulable » à l'emplacement du bouton, branche
`fix/tooling-coherence`).

**Chiffrage** (mise à jour Phase 3 — le doute d'architecture est LEVÉ :
`ModelBuilder` étend `ConfigurableLauncher` qui expose
`withCancellationToken`, javadoc Gradle API 9.8 — cf.
`AUDIT-PERFORMANCE.md` §2.8) :

| Élément | Travail | Risque |
|---|---|---|
| `codeide-tooling` : `SyncHandler` | `CancellationTokenSource` par sync, posé au `ModelBuilder` via `withCancellationToken` | **FAIBLE** (abaissé depuis MOYEN) |
| `codeide-tooling` : protocole | `CancelSyncRequest` (payload `syncId`) négocié par `PROTOCOL_VERSION` 11 + repli (client v10 ignoré, serveur v11 compatible) + tests `ProtocoleRoundTripTest` | FAIBLE — mécanique connue (même chemin que `CancelRequest`) |
| `codeide-tooling` : serveur | Garde sync 5 min → annulable ; état terminal de sync ANNULE publié (une seule source de vérité) | FAIBLE |
| À vérifier avant engagement | Comportement du daemon Gradle après annulation d'un `ModelBuilder` en configuration (connexion, caches partiels) | lecture ciblée, ~0,5 j-h |

**Total estimé** : ~2-3 jours-homme (inchangé).

## 2. Options de performance exposées dans la configuration (INC-32 — [ARBITRAGE])

**Règle préalable (interdit n°7)** : aucune option que le serveur ne lit
pas. Le MINIMUM OBLIGATOIRE de la Phase 1 (drapeau d'instrumentation +
export de diagnostic + banc) est **LIVRÉ** avec la branche miroir CodeIDE
`mesure/instrumentation-logs` (ADR 0152 — carte Diagnostic, drapeaux
passés par le daemon à chaque lancement).

**Proposition de sections** (chacune : option → ce que le serveur lit
réellement → mesure attendue) :

| Section | Option | Lu par le serveur aujourd'hui ? | Travail serveur si absent |
|---|---|---|---|
| Exécution | Mode hors ligne (libellé exact « builds, synchronisation et analyse du code ») | OUI (`--offline`) | aucun |
| Exécution | Arguments des builds / Arguments de sync SÉPARÉS (INC-31) | partiellement (arguments uniques) | séparer `argumentsBuild`/`argumentsSync` dans `SyncRequest` |
| Diagnostic | Niveau de journal du serveur (`--log-level`) | OUI (`ServerConfig.NiveauJournal`) | exposition UI seulement |
| Diagnostic | Instrumentation de latence + banc | OUI (`--instru-transport`, `--banc-*`) | **livré** (ADR 0152) |
| Diagnostic | Export des journaux | n/a (client) | aucun |
| État | Connexion, tas, daemon | partiel (tas orchestrateur) | exposer la mémoire du daemon si la Tooling API la rend |
| Actions | Redémarrer le serveur de build / arrêter le daemon Gradle / vider le cache de sync | bout manquant | `RestartServer`, `StopDaemon` (négociation protocole), vidage local |

**Priorité proposée** : (1) ~~drapeau + export~~ **fait** ; (2) champs
d'arguments séparés (INC-31 — impact perf direct, H-T5) ; (3) niveau de
journal ; (4) actions de redémarrage (INC-33) ; (5) le reste selon le
verdict.

## 3. Écarts priorisés de l'audit (impact × effort × risque)

Issus de `AUDIT-PERFORMANCE.md` — tri décroissant du rapport impact/effort :

| # | Écart | Impact attendu | Effort | Risque | Dépend de |
|---|---|---|---|---|---|
| E1 | **Empreinte serveur persistée** : `CacheSync` survit au redémarrage de l'orchestrateur (clé = SHA-256 des fichiers Gradle, miroir du `sync-state.json` client) ; la revalidation silencieuse devient un no-op quand rien n'a changé | **ÉLEVÉ** — supprime la quasi-totalité des 194 s dans le cas « rien n'a changé » (réouverture) | 2-3 j-h | FAIBLE (cache pur, invalider à la sync) | rien — [APRÈS VERDICT X7] pour mesurer le gain réel appareil |
| E2 | **`ResolvedDependenciesHandler` depuis le cache d'empreinte** : répondre sans re-résoudre quand l'empreinte n'a pas changé | MOYEN — 3,81 s bureau par requête répétée | 1 j-h | FAIBLE | E1 (même mécanisme) |
| E3 | **Journal de sync serveur** (T-F9 partiel) : « sync … conclu : N ms » + phases au `Journal` serveur, miroir des lignes client INC-44 | MOYEN — parité de diagnostic serveur/client, la séance X7 se lit des deux côtés | 0,5 j-h | FAIBLE | rien |
| E4 | **Seuils kover sur ce dépôt** (4 modules) | MOYEN — garde-fou de régression de couverture, parité avec CodeIDE | 0,5 j-h | FAIBLE | rien |
| E5 | **ADR du banc dans `:tooling:server`** (accès aux internes, partagé avec `ServeurIntegrationTest`) | FAIBLE — dette documentaire | 0,25 j-h | FAIBLE | rien |
| E6 | **Filtres de console** (avertissements / tâches réussies) côté CodeIDE | MOYEN — parité fenêtre Build (fiche 1) | 1 j-h | FAIBLE | volet vue, [APRÈS VERDICT] |
| E7 | **Liens fichier:ligne cliquables dans la console** | MOYEN — erreurs cliquables (fiche 1) | 2-3 j-h | MOYEN | virtualisation de la console (T-F volet client) |
| E8 | **Écran « Analyseur de build » minimal** (top tâches, phases, téléchargements) | MOYEN — parité Build Analyzer (fiche 3), assemblage d'états déjà transportés | 2-3 j-h | FAIBLE | verdict (pour savoir si les tâches dominent sur appareil) |
| E9 | **Options Gradle** : `--parallel`, build cache, étude configuration cache (T-F10) | À MESURER (le gain dépend du projet) | 0,5 j-h par option, mesure comprise | MOYEN (changements de comportement build) | [APRÈS VERDICT] — mesure avant/après obligatoire |

## 4. Vagues proposées (courtes, réversibles, critères de sortie mesurables)

- **Vague 0 — verdict d'abord (proprio)** : exécuter X1–X10
  (`DIAGNOSTIC-LOGS.md` §6) avec l'app construite en composite
  (`-Pfreres.sources`, `codeide-tooling` sur `mesure/instrumentation-logs`)
  — le verdict §8 fixe l'ordre des T-F. *Critère de sortie : verdict
  écrit, parts chiffrées par cause.*
- **Vague 1 — consolidation du diagnostic [peut démarrer]** : E3 + E4 + E5
  (aucun changement de comportement, aucun arbitrage). *Critère de sortie :
  lignes de conclusion de sync côté serveur visibles dans l'export ; kover
  vert en CI ; ADR mergé.*
- **Vague 2 — cache d'empreinte [après verdict X7]** : E1 puis E2.
  *Critère de sortie : réouverture d'un projet inchangé → revalidation
  < 1 s appareil (contre 194 s du journal) ; mesure avant/après au
  journal (INC-44 côté client).*
- **Vague 3 — parité console [après verdict]** : E6, puis E7/E8 selon les
  priorités du verdict ; correctifs T-F1…T-F10 dans l'ordre de la preuve.
- **Vagues d'arbitrage** : INC-12 (§1), INC-31/32/33 (§2) — attendent la
  décision du propriétaire, chiffrées.

## 5. Séparation des responsabilités

- **`codeide-tooling`** : tout ce qui touche le serveur et le protocole
  (E1-E5, E9, INC-12 serveur, T-F1, T-F3…T-F10 serveur) — branches
  dédiées, versionnées `v1.x`, jamais de fusion sans revue.
- **CodeIDE (client/vue)** : écran de configuration (§2), E6-E8, INC-31
  client, T-F2 (lecture tamponnée), virtualisation et lotissement de la
  console — branche `perf/console-logs` pour le volet B.
- **Propriétaire uniquement** : la séance de mesures appareil (X1–X10),
  le protocole de captures Android Studio
  (`PROTOCOLE-CAPTURES-ANDROID-STUDIO.md`), les arbitrages (INC-12,
  INC-31/32/33, T-F4 protocole batch) et la validation de chaque critère
  de sortie.

## 6. Correctifs de transport (rappel — APRÈS le verdict du volet A)

T-F1 à T-F10 du prompt §4 (écriture groupée, lecture tamponnée,
lotissement négocié `BuildOutputBatch`, réduction de production,
parseur hors fil, allocations, conclusion complète stdout+stderr —
INC-27/T-F8, instrumentation permanente et sobre, réglages Gradle) :
l'ORDRE sera fixé par les chiffres de la matrice X1–X10, chaque
correctif livré avec mesure avant/après sur les mêmes bancs, mêmes
appareil et binaire (règle 9 : release ET debug, écart rapporté).

## 7. Historique

- 2026-11-08 : création (volet C — INC-12 chiffré, INC-31/32/33 chiffrés,
  rappel T-F).
- 2026-11-08 : **extension Phase 3** — risque INC-12 abaissé (javadoc
  Tooling API), écarts E1–E9 issus de l'audit (empreinte serveur, cache
  des dépendances, journal de sync serveur, kover, parité console),
  vagues 0–3 avec critères de sortie, séparation des responsabilités.
