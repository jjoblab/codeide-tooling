# DIAGNOSTIC-LOGS — retard d'affichage des sorties de build (Phase 1, §3.5)

> **Statut : instrumentation LIVRÉE, verdict EN ATTENTE des mesures
> appareil.** Ce document est le livrable du STOP « verdict » : il pose la
> méthode, les bancs, la règle de décision et le protocole d'exécution. La
> réponse à la question du propriétaire (« le retard vient-il du tooling ou
> de CodeIDE ? ») sera écrite au §8 dès l'exécution de la matrice §6 sur
> l'appareil de référence — **aucun chiffre appareil n'est inventé ici**
> (interdit n°1 du prompt).
>
> Mission : prompt « retard d'affichage des sorties de build » du 2026-10-31
> (journal de terrain `codeide-logs-2026-10-31-110247.zip`). Branche :
> `mesure/instrumentation-logs`. Renvoi CodeIDE : `docs/TOOLING.md`
> (branche miroir `mesure/instrumentation-logs`).

## 1. La question à trancher

Pour trois builds `assembleDebug` volontairement en échec (≈ 60 lignes de
sortie chacun), la console affiche sa durée (« 36 s », « 12 s », « 12 s »)
mais les lignes n'apparaissent à l'écran que 17 à 32 s **après** leur
émission mesurée côté client (latence de transport max : 17,2 / 32,1 /
22,7 s), et jusqu'à 24 s **après la fin serveur** du build. La publication
(flux → zone texte) ne coûte que 4–5 ms (T4−T3). La cause est donc à
chercher en amont de la vue : coût **par frame** sur le chemin
serveur → socket → fil lecteur (H-T8, H-S1, H-S2), retard **avant T0**
(H-T7), ou contention système. Les faits F1–F7, E1–E7 et la hiérarchie
d'hypothèses du prompt §2.3–§2.5 font foi et ne sont pas répétés ici.

## 2. Règle de décision (écrite AVANT de mesurer — conservée telle quelle, §3.4)

- Si **X1 (release, repos)** montre un coût par frame ≥ ~50 ms → **défaut
  de transport** (framing, appels système, lecteur) : correctif dans
  `codeide-tooling` (écriture) et CodeIDE (lecture).
- Si X1 est bas (< ~10 ms/frame) mais **X3 ou X4** reproduisent le profil
  du journal → **contention système** : le correctif est de **réduire le
  nombre d'appels/réveils par frame** (écriture unique, lecture tamponnée,
  lotissement), de **réduire l'empreinte mémoire** (daemon, LSP) et de
  **dire honnêtement** à l'utilisateur quand le système est saturé.
- Si X1 est bas **et** X6 montre `T0 − T-1` grand → **retard côté
  Gradle/Tooling API** (H-T7) : réglages Gradle, tampon de sortie, statut
  honnête (« le daemon travaille ») ; documente ce qui n'est pas
  corrigeable.
- Si `T3 → T5` est grand (lots longs, texte qui croît, images perdues) →
  **cause = vue CodeIDE**.
- Si plusieurs causes : quantifie la **part de chacune** (différence des
  maxima, comme le prévoit l'ADR 0081).
- Établis **la dépendance à la taille** (X9) et **la dépendance au binaire**
  (X2).

## 3. Points de mesure — état après cette livraison

| Point | Où | Mesure | État |
|---|---|---|---|
| T-1 | Daemon Gradle (métronome) | émission `[[CIDE-T seq epochMs]]` toutes les 250 ms | **livré** (script d'init généré, écarts consignés) |
| T0 | `StreamingOutputStream.viderLigne` | `timestampMs` | existant |
| T1 | `EventBus.file` | attente du `put()` bloquant | **livré** (sous drapeau) |
| **T1b** | `SocketClient.fileNormale` | profondeur à l'enfilement/défilement, **âge de la plus ancienne frame**, maxima | **livré** (âges/maxima TOUJOURS actifs ; détails sous drapeau) |
| T2 | consommateur `gradle-server-evenements` | durée poll→encode→envoyer par événement, **comptage par type** | **livré** (sous drapeau) |
| **T2b** | `gradle-server-ecrivain` | durée d'écriture, **nombre d'appels système par frame** (flux compteur), CPU fil vs mur | **livré** (sous drapeau) |
| T3 | fil `tooling-lecteur` (app) | latence transport (existe) + durées read/décodage/router par frame | existant (min/moy/max) ; le détail par phase reste côté app (branche miroir CodeIDE) |
| **T3b** | fils serveur | `/proc/self/task/<tid>/schedstat` avant/après build | **livré** (sous drapeau, par TID — le noyau tronque `comm` à 15 caractères, les deux fils du transport y sont indiscernables) |
| T4 | `GradleService` (app) | latence de publication | existant |
| T5 | `PanneauConsoleFragment.viderLot` (app) | durée du lot, longueur du texte | **à livrer côté app** (branche miroir) |
| T6 | `Choreographer` (app) | images perdues, layout/dessin | **à livrer côté app** (branche miroir) |
| Système | app + orchestrateur | meminfo, onTrimMemory, GC | partiellement existant (F6) ; consolidé côté app |

### Ligne de conclusion de build corrigée (angle mort n°3)

```
build <id> conclu : <durée> ms (reussi=…, annule=…, gradle=… ms,
  file=evenements:<N>/ecriture: <M> (max <P>, age max <A> ms))
```

La file de l'EventBus **et** la file d'écriture du socket sont rapportées,
avec la profondeur **maximale** atteinte et l'**âge maximal** d'une frame —
plus seulement la valeur finale (qui valait 0-1 dans le journal du
propriétaire et ne prouvait rien). Le seuil d'alerte passe de « 512 frames »
(hors d'atteinte à ≈ 60 lignes) à la règle **relative et temporelle** :
profondeur ≥ 32 **ou** âge de la plus ancienne frame ≥ 1 s (borné à un
avertissement toutes les 5 s).

### Résumé d'instrumentation (drapeau actif uniquement)

Après chaque conclusion de build, une ligne `instru: build <id> — …` porte :
T1 (nombre de publications, attente max du put), T2 (par type de message :
nombre, durée moyenne/max), T1b (profondeurs max, âge max), T2b (frames,
durée moyenne/max d'écriture, appels système par frame), CPU des deux fils
vs temps mur, T3b (schedstat : attente de file d'exécution et tranches),
métronome (nombre de repères reçus, écart min/moy/max à T0).

## 4. Bancs livrés

### 4.1 Banc « socket seul » (§3.1b) — prioritaire

`--banc-socket-seul` : l'orchestrateur **complet** (vrai handshake, vraie
pile EventBus → SocketClient → FrameCodec, deux files, fil écrivain) émet
une séquence `BuildStarted` → N `BuildOutput` → `BuildFinished` **sans
Gradle**, puis reprend sa boucle normale (le pong répond, l'app reste
connectée, sa console affiche le cycle et sa sonde `ConsoleLatence`
résume la latence de transport au `BuildFinished`).

Paramètres : `--banc-lignes <n>` (défaut 60 — profil du journal),
`--banc-cadence-ms <n>` (défaut 0 = rafale ; 50 ms ≈ 20 lignes/s ; 200 ms
= 5 lignes/s), `--banc-longueur-ligne <n>` (défaut 80), `--banc-contenu
plain|ansi|utf8|multi` (traces d'exception multi-lignes, ANSI, UTF-8
multi-octets). Il vit dans `:tooling:server` (et non `:tooling:testing`)
parce qu'il a besoin des **internes** de la pile — même discipline que
`ServeurIntegrationTest`, dont il partage le harnais. Vérifié JVM par
`BancSocketSeulTest` : ordre (started < lignes < finished), intégralité,
alternance stdout/stderr, buildId sur chaque ligne.

**Exécution sur appareil** (nécessite le crochet côté app, branche miroir
CodeIDE — voir §7) : l'app lance `gradle-server.jar` avec les drapeaux du
banc au lieu des siens ; le propriétaire relance le serveur de build pour
chaque configuration. Aucune mesure n'est faite par le banc lui-même : les
chiffres viennent des lignes `instru:` (serveur) et `ConsoleLatence` (app).

### 4.2 Métronome du daemon (§3.1c)

Script d'init `metronome-codeide.init.gradle.kts` généré à côté du socket
(même mécanisme que l'applog, ADR 0103), passé aux builds par `-I` sous le
drapeau `--instru-transport` uniquement. Le fil (démon, « codeide-metronome »)
imprime `[[CIDE-T <seq> <epochMs>]]` sur stdout toutes les 250 ms ;
il s'arrête à l'apparition du fichier `metronome-codeide.arret` (créé à la
conclusion du build par l'orchestrateur — le seul signal de fin fiable
entre les deux process sans toucher au protocole) ou au garde-fou de
10 minutes (serveur mort). Les repères sont **filtrés de l'affichage**
(consommés avant publication — jamais un `BuildOutput`) et leurs écarts à
T0 alimentent le résumé (H-T7 : retard avant T0).

**Limite documentée** : le fil démarre à l'application du script (avant
l'évaluation des réglages) et couvre donc configuration + exécution ; si le
daemon ne relaye pas la sortie de configuration vers la Tooling API, le
résumé dira « aucun repere recu » — c'est déjà un diagnostic. La vérification
du partage d'horloge (§3.1c : « échange ping/pong avec horodatages ») se
lit dans l'export : les lignes `instru: metronome seq=… ecartT0=…` à cadence
constante dont l'écart serait **négatif** désignent des horloges divergentes
(les deux process lisent le même CLOCK_REALTIME du noyau : la divergence
n'est attendue que si un NTP recale l'horloge en pleine session). Un vrai
échange ping/pong horodaté exigerait un changement de format (Phase 2,
négocié par PROTOCOL_VERSION).

### 4.3 Banc déluge (§3.1a)

Le même émetteur porte le déluge : `--banc-lignes 10000|50000|200000` à
cadence réglable, longueurs 40/200/2000, contenus ansi/utf8/multi. Le « vrai
build Android » du corpus reste les fixtures de `:tooling:testing`
(`ServeurIntegrationTest`, 15 intégrations réelles) — avec le métronome
drapeau actif, un build de fixture mesure T-1/T0 côté JVM (validation du
mécanisme ; les chiffres appareil restent au propriétaire).

## 5. Coût de l'instrumentation (règle 10 : mesuré)

Banc JVM du bac à sable (2 CPU, 20 000 lignes en rafale de 200 caractères,
meilleur de 3 passages après réchauffement) : **442 ms sans drapeau vs
463 ms avec** — ≈ **+1 µs par ligne**, dans le bruit de mesure. Drapeau
coupé : aucune ligne `instru:`, aucun compteur, le flux compteur d'appels
n'est pas posé ; seuls les âges/profondeurs maxima des files restent suivis
(deux atomiques par frame — c'est le correctif de l'angle mort n°3, voulu
toujours actif). **À re-mesurer sur l'appareil de référence avec le même
protocole** (X1 vs X1-sans-drapeau) — le coût y sera probablement supérieur
(ART/ARM).

## 6. Matrice d'expériences (à exécuter sur l'appareil de référence)

Appareil : moto g06, Android 15 (API 35), arm64-v8a. Chaque expérience :
**médiane et p95 sur ≥ 5 répétitions** (règle §3.3). Builds **release ET
debug** (règle 9), l'écart rapporté.

| # | Expérience | Protocole | Question |
|---|---|---|---|
| X1 | Banc socket seul, **release**, appareil au repos | `--banc-lignes 60` (rafale) puis `--banc-cadence-ms 50` (20 lignes/s) ; relever `instru:` + `ConsoleLatence` | Le coût **par frame** est-il anormal **sans** Gradle ni charge ? |
| X2 | X1 en **debug** | idem, build debug | Quelle part vient du binaire debug (H-S2) ? |
| X3 | X1 + **charge CPU contrôlée** | X1 pendant un build d'arrière-plan / fils occupés dans l'app | La contention CPU suffit-elle à produire 0,3–0,5 s/frame ? |
| X4 | X1 + **pression mémoire** | daemon Gradle résident + sessions LSP ouvertes, puis allocation jusqu'à `onTrimMemory` (F6) | La pression mémoire (H-S1) produit-elle le profil du journal ? |
| X5 | X1 avec **écriture en un seul appel + lecture tamponnée** | variante derrière drapeau (Phase 2, T-F1/T-F2 — octets du fil inchangés) | Le nombre d'appels/réveils par frame explique-t-il le coût (H-T8) ? |
| X6 | Build réel avec **métronome** | 3× `assembleDebug` du scénario du propriétaire, drapeau actif ; relever T-1, T0, T3, T4 | Où se forme le retard : avant T0, T0→T3, T3→T4, ou vue ? |
| X7 | X6 pendant la **sync** | même instrumentation côté sync (crochet app à livrer) | La sync de 194 s souffre-t-elle du même retard ? |
| X8 | **Comptage de frames par type** sur build réel | résumé T2 (drapeau actif) | Combien de frames hors `BuildOutput` ? (angle mort n°5) |
| X9 | Déluge 10 k / 50 k lignes | `--banc-lignes 10000/50000`, longueurs 40/200/2000 | Coût d'affichage **par ligne vs lignes déjà affichées** — O(1) ou O(n) dans la vue (H-V1) ? |
| X10 | Build réel avec `--info` | « arguments supplémentaires » de la configuration | Effet sur le volume (H-T5) sur build, sync et classpath LSP |

## 7. Ce qui reste à livrer côté app (branche miroir CodeIDE)

Le volet serveur est complet ; le miroir client (`mesure/instrumentation-logs`
de CodeIDE) porte : le passage de `--instru-transport` (et des drapeaux du
banc) par le daemon avec le réglage dans l'écran de configuration (INC-32,
minimum obligatoire : drapeau + export), le crochet de lancement du banc,
T3 détaillé (durées read/décodage/router par frame au fil lecteur), T5
(viderLot) et T6 (Choreographer/FrameMetrics), l'accumulateur de latence de
la sync (INC-44) et la lecture consolidée des signaux système (meminfo,
onTrimMemory). **Ces points ne bloquent pas X1–X6** si le propriétaire
accepte de lancer le banc à la main (adb) ; ils automatisent la séance.

## 8. Verdict (À COMPLÉTER après exécution de §6)

- [ ] X1 (release, repos) : coût par frame = ____ ms → seuil ~50 ms :
- [ ] X3/X4 reproduisent le profil du journal : oui / non
- [ ] X6 : T-1→T0 = ____ ; T0→T3 = ____ ; T3→T4 = ____ ; vue = ____
- [ ] Dépendance à la taille (X9) : O(1) / O(n)
- [ ] Dépendance au binaire (X2) : ×____
- **Verdict : tooling / CodeIDE / système / combiné — parts : ______**

La réponse à la question du propriétaire s'écrit ici, chiffres à l'appui,
après la séance appareil. Rien d'autre ne la précède.

## 9. Historique

- 2026-11-01 : livraison de l'instrumentation (T1, T1b, T2, T2b, T3b,
  métronome, bancs, conclusion de build complète, règle d'alerte
  relative/temporelle) ; chaîne du README verte (spotlessCheck, detekt,
  checkModuleDependencies, 166 tests dont 15 intégrations réelles, shadowJar
  8,1 Mo) ; coût JVM mesuré (§5). Vérification appareil : en attente du
  propriétaire.
