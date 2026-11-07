# PLAN-AMELIORATIONS — tooling « niveau Android Studio »

> **Statut : document d'arbitrage du propriétaire.** Ce plan est livré par
> la mission « retard d'affichage des sorties de build » (volets A et C).
> Le volet A est **STOPPÉ au verdict** (voir `DIAGNOSTIC-LOGS.md` §8 : les
> mesures appareil restent à exécuter par le propriétaire) — les correctifs
> de transport (T-F1 à T-F10) ne s'appliqueront qu'APRÈS ce verdict, dans
> l'ordre de la preuve. Les deux fonctions ci-dessous sont chiffrées pour
> décision ; **aucune n'est implémentée** (interdit n°5 du prompt : aucune
> fonction nouvelle sans accord).

## 1. Annulation de la synchronisation (INC-12 — arbitrage demandé)

**Vécu** : sync de 194 s sans pouvoir arrêter ; garde serveur de 5 min
(`Ids.kt`) ; aucun libellé ne l'explique. Côté CodeIDE, l'honnêteté est
livrée (étiquette « Sync non annulable » à l'emplacement du bouton Arrêter,
branche `fix/tooling-coherence`).

**Chiffrage de l'annulation RÉELLE** (estimations de coût de mise en
œuvre, à valider) :

| Élément | Travail | Risque |
|---|---|---|
| `codeide-tooling` : `SyncHandler` | Exposer un jeton d'annulation Tooling API (`BuildLauncher` a `cancellationToken`; l'équivalent sync passe par la même requête `launch`/modèle — à vérifier contre la Tooling API 9.7.1 : la sync est un `ModelBuilder`, l'API de cancellation n'y est PAS symétrique du build → il faudra probablement tuer/réutiliser le daemon ou passer par `withCancellation` du protocole interne) | MOYEN — dépend de l'API réelle |
| `codeide-tooling` : protocole | `CancelSyncRequest` (payload `syncId`) négocié par `PROTOCOL_VERSION` 11 + repli (un client v10 qui l'envoie est ignoré, un serveur v11 reste compatible) + tests `ProtocoleRoundTripTest` | FAIBLE — mécanique connue (même chemin que `CancelRequest`) |
| `codeide-tooling` : serveur | Garde sync 5 min → annulable ; état `SyncCancelled` publié (une seule source de vérité) | FAIBLE |
| CodeIDE : client + UI | Action `AnnulerSync` ; le bouton Arrêter devient actif pendant une sync (l'étiquette d'honnêteté disparaît) ; notification « Sync annulée » | FAIBLE |

**Total estimé** : ~2-3 jours-homme, le risque porte sur la symétrie de
l'annulation dans la Tooling API pour les `ModelBuilder` — à trancher par
une lecture ciblée AVANT tout engagement.

## 2. Options de performance exposées dans la configuration (INC-32 — arbitrage demandé)

**Règle préalable (interdit n°7)** : aucune option que le serveur ne lit
pas. Le MINIMUM OBLIGATOIRE de la Phase 1 (drapeau d'instrumentation +
export de diagnostic) est livré avec la branche miroir CodeIDE de
l'instrumentation — les drapeaux du banc passent par la ligne de commande
du serveur (`--instru-transport`, `--banc-socket-seul`, §DIAGNOSTIC-LOGS).

**Proposition de sections** (chacune : option → ce que le serveur lit
réellement → mesure attendue) :

| Section | Option | Lu par le serveur aujourd'hui ? | Travail serveur si absent |
|---|---|---|---|
| Exécution | Mode hors ligne (libellé exact « builds, synchronisation et analyse du code ») | OUI (`--offline` dans les arguments) | aucun |
| Exécution | Arguments des builds / Arguments de sync (champs SÉPARÉS — INC-31 : `--info` suggéré en exemple part aujourd'hui partout, y compris classpath LSP) | partiellement (arguments uniques) | séparer `argumentsBuild`/`argumentsSync` dans `SyncRequest` |
| Diagnostic | Niveau de journal du serveur (`--log-level`) | OUI (`ServerConfig.NiveauJournal`) | exposition UI seulement |
| Diagnostic | Instrumentation de latence (Phase 1) | OUI (`--instru-transport`) | exposition UI seulement |
| Diagnostic | Export des journaux | n/a (client) | aucun |
| État | Connexion, tas, daemon | partiel (tas orchestrateur) | exposer la mémoire du daemon si la Tooling API la rend |
| Actions | Redémarrer le serveur de build / arrêter le daemon Gradle / vider le cache de sync | bout manquant | `RestartServer`, `StopDaemon` (négociation protocole), vidage local |

**Priorité proposée** : (1) drapeau instrumentation + export (bloquant
Phase 1), (2) champs d'arguments séparés (INC-31 — impact perf direct,
H-T5), (3) niveau de journal, (4) actions de redémarrage (INC-33), (5) le
reste selon le verdict du volet A.

## 3. Correctifs de transport (rappel — APRÈS le verdict du volet A)

T-F1 à T-F10 du prompt §4 (écriture groupée, lecture tamponnée,
lotissement négocié `BuildOutputBatch`, réduction de production,
parseur hors fil, allocations, conclusion complète stdout+stderr —
INC-27/T-F8, instrumentation permanente, réglages Gradle) :
l'ORDRE sera fixé par les chiffres de la matrice X1-X10
(DIAGNOSTIC-LOGS §6), chaque correctif livré avec mesure avant/après sur
les mêmes bancs.

## 4. Historique

- 2026-11-08 : création (volet C — INC-12 chiffré suite à l'honnêteté
  livrée côté CodeIDE ; INC-31/32/33 chiffrés ; rappel de l'ordre
  T-F après verdict).
