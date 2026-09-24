# tooling:daemon — daemon du tooling Gradle

## Rôle

Le cycle de vie de l'orchestrateur JVM (§5.4 du prompt compagnon Tooling).
`DaemonManager` déploie le JAR, ouvre l'écoute, lance le process, valide
sa connexion, le surveille et le relance — les états de connexion
(`EN_CONNEXION`/`ECHOUEE` inclus, ADR 0041 décision 8) sont animés ici.

Le module ne redéfinit RIEN de ce qui existe : le lancement passe par le
port `NativeProcessLauncher` du domaine (implémentation `core:bootstrap`,
environnement canonique Termux), l'écoute et la session sont celles de
`tooling:client` (enveloppées), le JAR vient des assets (artefact de build
ADR 0040).

## Pièces (§5.4)

| Classe | Rôle |
|---|---|
| `DaemonManager` | machine d'états : déploiement → écoute (AVANT le lancement, §5.1) → lancement → handshake → surveillance (santé + sorties) → mort → relance bornée. Échecs DÉFINITIFS : handshake refusé, arguments invalides (code 2), JAR indisponible ; JDK absent = état `DECONNECTEE` sans lancement (le bootstrap de l'écran Terminal peut arriver ensuite) |
| `JarDeployer` | copie atomique (`.tmp` + renommage) du JAR des assets vers `filesDir/tooling/` ; **marqueur de version** = SHA-256 de la source : un redémarrage sur un JAR inchangé ne recopie rien |
| `SourceJarTooling` / `SourceJarAssets` | couture de test / lecture `AssetManager` (`tooling/gradle-server.jar`, contrôlé par `preBuild`) |
| `HoteSocketTooling` / `HoteSocketAndroid` | couture de test / enveloppe du `GradleSocketServer` de `tooling:client` (la colle `LocalSocket` reste concentrée là-bas) |
| `GenerateurSecret` | secret de handshake frais (`SecureRandom`, 32 octets URL-safe) par tentative — jamais écrit, jamais journalisé (§4.4) |
| `ModuleDaemon` | câblage Hilt (répertoires privés, version du paquet, coutures de production) |

## Santé et relances (§5.4)

- **Health check** : un `PingMessage` toutes les 5 s
  (`HEARTBEAT_INTERVAL_MS`) sur la session ouverte ; le repère
  `GradleApiImpl.dernierPongMs` (rafraîchi par le pompe du client à chaque
  `PongMessage`, initialisé à l'ouverture) vieillissant au-delà de 15 s
  (`HEARTBEAT_TIMEOUT_MS`) → arrêt forcé du process → relance.
- **Relances bornées** à `MAX_RECONNECT_ATTEMPTS` (5) avec repli
  exponentiel borné (1 s → 10 s) ; épuisement → `ECHOUEE` (échec définitif
  jusqu'à un nouveau `demarrer`).
- **Sorties du process = son journal** (règle 14, ADR 0040) : stderr →
  `AppLogger` WARN, stdout → INFO, tag `gradle-server` — le journal
  applicatif (onglet Journal de l'éditeur, écran Diagnostic) reçoit tout.
- **Nettoyage synchrone** dans les terminaisons (leçon T2) :
  `kill`/`fermer`/`fermerSession` ne suspendent jamais — l'annulation de la
  surveillance ne laisse rien vivant.

## Démarrage

`CodeIdeApplication` (processus principal) : `demarrer(porteeDemarrage)` à
la création, puis re-déclenchement idempotent quand l'installation du
bootstrap aboutit (`BootstrapInstaller.etat` → `Terminee`). La mort de
l'app ferme le socket → l'orchestrateur voit l'EOF et s'arrête seul (code
0) : aucun process orphelin.

## Tests

- `DaemonManagerTest` (8) sur fakes (`ProcessusMaitrise`, hôte factice,
  sessions factices) : ordre écoute-avant-lancement, secret transmis et
  validé, stderr → journal, relance avec secret neuf, épuisement des 5
  tentatives → `ECHOUEE`, JDK absent sans lancement, handshake refusé
  définitif, orchestrateur muet tué par le health check, `arreter` sans
  relance.
- `JarDeployerTest` (4) : première copie + marqueur, pas de recopie sans
  changement, recopie au changement, source absente → `IOException`.
- `BoutEnBoutTest` (1) — **§7.4, premier bout-en-bout réel** : le daemon
  lance le VRAI orchestrateur en sous-processus `java` (`ServerMain` par
  classpath, l'artefact shadowJar n'existant pas en JVM de test) sur un
  VRAI socket Unix (`ServerSocketChannel` JDK, miroir de l'astuce Android),
  puis exécute un VRAI build Gradle sur la fixture `minimal-java` :
  connexion, pong réel, sortie ligne à ligne, état `REUSSI`, arrêt propre.

La colle `LocalSocket` (via `HoteSocketAndroid`) est filtrée du kover —
même politique que G3 (ADR 0041 décision 6) ; le cœur (`DaemonManager`,
`JarDeployer`, `GenerateurSecret`) est couvert par les fakes, le dialogue
réel par le bout-en-bout.

## Dépendances (gelées par `ModuleRulesPlugin`)

Production : `:tooling:client` (façade + sessions), `:core:domain`
(`NativeProcessLauncher`, `ToolchainLocator`, `AppLogger`,
`DispatcherProvider`), `:tooling:protocol` (transitif). Test uniquement :
`:tooling:server` (le VRAI orchestrateur du bout-en-bout §7.4 — ADR 0042),
`:tooling:testing` (fixtures Gradle), `:core:testing` (fakes).
