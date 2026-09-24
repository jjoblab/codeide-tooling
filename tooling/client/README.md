# tooling:client — client Android du tooling Gradle

## Rôle

Le côté Android du dialogue client-serveur du tooling (§5 du prompt
compagnon Tooling). Architecture inversée par rapport à l'intuition : **l'app
est le SERVEUR du socket Unix, l'orchestrateur JVM (`tooling:server`, G2)
est le CLIENT** — l'écoute s'ouvre AVANT le lancement du process (G4), donc
aucun fichier de découverte de port, aucun polling.

Ce module ne lance AUCUN process : il écoute, valide le handshake, puis
traduit le protocole vers les modèles du domaine à travers la façade
publique `GradleToolingRepository` (port de `core:domain`, §5.3 — les
features n'injectent jamais une implémentation tooling, règle §2.2).

## Pièces (§5.1 à §5.3)

| Classe | Rôle |
|---|---|
| `GradleSocketServer` | écoute `filesDir/run/gradle.sock` (§5.1) : répertoire privé `0700`, résidu retiré, `LocalSocket.bind(FILESYSTEM)` + `LocalServerSocket(FileDescriptor)` (namespace FICHIER obligatoire — voir ADR 0041), accepte UNE connexion avec délai (§7.5) |
| `HandshakeApp` | validation CÔTÉ APP du `HelloRequest` (§4.4) : secret invalide ou version incompatible → `ErrorResponse` typée envoyée PUIS connexion fermée — AUCUNE requête n'atteint un handler avant validation |
| `SessionTooling` / `SessionSocketAndroid` | couture de test (§7.3) / session réelle sur `LocalSocket` : écritures sérialisées par verrou, lectures en flux froid bloquant sur `Dispatchers.IO`, EOF = fin de flux (déconnexion) |
| `GradleApiImpl` | implémentation de référence de `GradleToolingRepository` (§5.3) : corrélation requête/réponse par livre de promesses (`CompletableDeferred` par identifiant, écho §3.2), canaux de sortie bornés, états en `StateFlow` |
| `ModuleToolingClient` | câblage Hilt : `@Binds GradleApiImpl → GradleToolingRepository` |

## Diffusion sans perte (§5.2)

- **Sortie des builds** : un `Channel(4096)` PAR `buildId`, `send()`
  suspendant — contre-pression, jamais `DROP_OLDEST`, jamais de conflation.
  Le canal est créé AVANT l'envoi de la requête et fermé à `BuildFinished`
  SANS être retiré : un collecteur tardif (onglet Sortie ouvert après le
  build, rotation) draine le tampon puis complète — ré-observer un build
  terminé rejoue son historique.
- **États (build, tas, connexion, diagnostics)** : `StateFlow` — conflation
  LÉGITIME, seul l'état courant compte.

## Tests (§7.3)

19 tests sur `SessionFactice` (le « SocketClient fake » du prompt) :

- `GradleApiImplTest` (16) : lignes dans l'ordre + état final, **non-conflation
  sous forte charge (12 000 lignes émises d'un trait, aucune perdue — LE test
  §7.3)**, corrélation d'identifiant, erreur typée → `AppResult.Failure`,
  sync partielle = succès partiel, annulation, tas/connexion, sans session =
  échecs typés (jamais de blocage muet), échec d'envoi, stderr ≠ stdout,
  message d'échec, remplacement de session, déconnexion rompt les promesses ;
- `HandshakeAppTest` (3) : bon secret → `HelloResponse`, secret invalide →
  erreur typée puis fermeture, version incompatible → `PROTOCOL_VERSION_MISMATCH`.

La colle `LocalSocket`/`LocalServerSocket` n'est pas exécutée sous Robolectric
(aucune shadow — vérifié 4.17) : couverture kover filtrée pour
`GradleSocketServer`/`SessionSocketAndroid` + code généré Hilt, la LOGIQUE
(handshake, façade) est couverte à ≥ 80 % via la couture — même précédent
documenté que la colle Termux/JNI de `core:terminal-runtime`.

## Dépendances

`core:domain` (port public) · `tooling:protocol` (framing + messages) ·
`tooling:api` — toutes en `api` (les mappers vivent ici). Aucune dépendance
Termux, aucune redéfinition de `ToolchainLocator`/`ProcessEnvironmentProvider`/
`NativeProcessLauncher` (§8) : le lancement du process est le travail de G4.
