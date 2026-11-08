# PLAN-AMELIORATIONS — tooling « niveau Android Studio » (Phase 3, §5.4)

> **Statut : mise à jour du 2026-11-08 (session Phase 2).** Le verdict
> du volet A est RENDU (`DIAGNOSTIC-LOGS.md` §8 : retard = transport du
> JAR, corrigé en 1.1.1) — la Phase 2 s'est ouverte sur SES chiffres :
> **T-F1/T-F2/T-F3/T-F8 livrés en 1.2.0**, E1/E2/E3 livrés, INC-31
> livré (protocole + serveur + écran de configuration CodeIDE). Les
> fonctions marquées **[ARBITRAGE]** restent soumises au propriétaire
> (interdit n°5) ; les chantiers marqués **[APRÈS VERDICT]** sont
> débloqués au cas par cas selon §8.3.

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
| E1 | **Empreinte serveur persistée** : `CacheSync` survit au redémarrage de l'orchestrateur (clé = SHA-256 des fichiers Gradle + contexte d'exécution, miroir élargi du `sync-state.json` client) ; la revalidation silencieuse devient un no-op quand rien n'a changé | **ÉLEVÉ** — supprime la quasi-totalité des 194 s dans le cas « rien n'a changé » (réouverture) | 2-3 j-h | FAIBLE (cache pur, invalider à la sync) | **LIVRÉ en 1.2.0** (`EmpreinteProjet` + `CacheSyncPersistant`, restitution annoncée en console et au journal) |
| E2 | **`ResolvedDependenciesHandler` depuis le cache d'empreinte** : répondre sans re-résoudre quand l'empreinte n'a pas changé | MOYEN — 3,81 s bureau par requête répétée | 1 j-h | FAIBLE | **LIVRÉ en 1.2.0** (arbres extraits au fil de la sync, complétés au premier accès hors sync) |
| E3 | **Journal de sync serveur** (T-F9 partiel) : « sync … conclu : N ms » + phases au `Journal` serveur, miroir des lignes client INC-44 | MOYEN — parité de diagnostic serveur/client, la séance X7 se lit des deux côtés | 0,5 j-h | FAIBLE | **LIVRÉ en 1.2.0** (conclusion + résumé de phases, restitution incluse) |
| E4 | **Seuils kover sur ce dépôt** (4 modules) | MOYEN — garde-fou de régression de couverture, parité avec CodeIDE | 0,5 j-h | FAIBLE | à livrer (le seuil serveur 75 % existe, les 3 autres modules restent) |
| E5 | **ADR du banc dans `:tooling:server`** (accès aux internes, partagé avec `ServeurIntegrationTest`) | FAIBLE — dette documentaire | 0,25 j-h | FAIBLE | **LIVRÉ en 1.2.0** (`docs/ADR-BANC-SERVEUR.md`) |
| E6 | **Filtres de console** (avertissements / tâches réussies) côté CodeIDE | MOYEN — parité fenêtre Build (fiche 1) | 1 j-h | FAIBLE | volet vue — à arbitrer (le verdict a clos le retard, la parité reste un confort) |
| E7 | **Liens fichier:ligne cliquables dans la console** | MOYEN — erreurs cliquables (fiche 1) | 2-3 j-h | MOYEN | virtualisation de la console (T-F volet client) |
| E8 | **Écran « Analyseur de build » minimal** (top tâches, phases, téléchargements) | MOYEN — parité Build Analyzer (fiche 3), assemblage d'états déjà transportés | 2-3 j-h | FAIBLE | [ARBITRAGE] — à arbitrer (X8 a chiffré la matière : 39 TaskStarted par build ignorés aujourd'hui) |
| E9 | **Options Gradle** : `--parallel`, build cache, étude configuration cache (T-F10) | À MESURER (le gain dépend du projet) | 0,5 j-h par option, mesure comprise | MOYEN (changements de comportement build) | [APRÈS MESURE] — mesure avant/après obligatoire (X10 non exécuté) |

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

## 6. Correctifs de transport (Phase 2 — verdict rendu, ordre fixé par §8)

Verdict du 2026-11-08 (`DIAGNOSTIC-LOGS.md` §8) : le retard était le
transport du JAR (interblocage JDK 17, corrigé en 1.1.1) ; la latence
résiduelle appareil est de 14–102 ms selon les flux. État après 1.2.0 :

| Correctif | État | Preuve / justification |
|---|---|---|
| T-F1 écriture de frame en un appel | **LIVRÉ** | `FrameCodec.writeFrame` réunit en-tête+payload (1 appel `write`, contre 4+1) ; `FrameCodecTest` verrouille octets inchangés et appel unique |
| T-F2 lecture tamponnée côté client | **LIVRÉ (protocole + app)** | en-tête lu en UN `read` bouclé (sémantique EOF/troncature inchangée, testée) ; l'app enveloppe le canal d'un `BufferedInputStream` 64 Kio — octets du fil inchangés |
| T-F3 vidange groupée du consommateur | **LIVRÉ** | l'écrivain du `SocketClient` groupe les frames DÉJÀ en file (plafonds 32 frames / 256 Kio, AUCUNE attente ajoutée) — `writeFrames` en un appel système |
| T-F4 lotissement négocié `BuildOutputBatch` | **REPORTÉ [ARBITRAGE]** | la latence p95 appareil (42–102 ms max) est sous la cible proposée (< 250 ms) : le bénéfice marginal ne justifie pas un changement de fil négocié aujourd'hui |
| T-F5 ne plus produire ce qui n'est pas affiché | **CHIFFRÉ, PAS IMPLÉMENTÉ** | X8 : ~2,4 frames par ligne affichée (39 TaskStarted par build) — à ~1–2 ms/frame post-correctif, l'impact résiduel est ~100 ms par build ; à revisiter AVEC E8 (l'analyseur consommerait ces événements) |
| T-F6 parseur de diagnostics hors fil de pompage | **NON NECESSAIRE (chiffres)** | T2 appareil : BuildOutput moy 1–2 ms, max 28 ms — le parseur ne domine aucun profil post-verdict ; à rouvrir si X9 (déluge) le montre |
| T-F7 allocations par ligne | **PARTIEL** | le tampon de groupe est réutilisé par écriture ; l'encodage JSON par événement reste (mesuré non dominant) ; UUID par ligne : les identifiants du protocole sont inchangés par contrat (règle 2) |
| T-F8 conclusion complète stdout+stderr | **LIVRÉ** | l'observateur stderr nourrit la conclusion : `gradle=` renseigné sur les builds ÉCHOUÉS (INC-27 clos ; les 7 conclusions « inconnue » de la capture 01:17 étaient toutes des échecs volontaires) |
| T-F9 instrumentation permanente et sobre | **LIVRÉ (volet sync)** | journal de sync serveur E3 (conclusion + phases + restitution) ; le build avait déjà sa ligne ; la sonde reste sous drapeau (règle 10) |
| T-F10 réglages Gradle | **DOCUMENTÉ [APRÈS MESURE]** | E9 : chaque option exige une mesure avant/après sur appareil (X10 non exécuté) ; les arguments libres (dont `--parallel`) restent passables par l'écran de configuration |

Règle maintenue : tout nouveau correctif se livre avec mesure avant/après
sur les mêmes bancs, mêmes appareil et binaire (règle 9 : release ET
debug, écart rapporté).

## 7. Historique

- 2026-11-08 : création (volet C — INC-12 chiffré, INC-31/32/33 chiffrés,
  rappel T-F).
- 2026-11-08 : **extension Phase 3** — risque INC-12 abaissé (javadoc
  Tooling API), écarts E1–E9 issus de l'audit (empreinte serveur, cache
  des dépendances, journal de sync serveur, kover, parité console),
  vagues 0–3 avec critères de sortie, séparation des responsabilités.
- 2026-11-08 (session Phase 2) : verdict rendu (DIAGNOSTIC-LOGS §8) ;
  E1/E2/E3/E5 et T-F1/T-F2/T-F3/T-F8 **livrés en 1.2.0** ; INC-31 livré
  (champ `argumentsSync` négocié par tolérance de champ, séparé dans
  l'écran de configuration CodeIDE) ; T-F4/T-F5/E8 arbitres ouverts,
  T-F6 clos par les chiffres.
