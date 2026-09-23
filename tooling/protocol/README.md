# tooling:protocol

Protocole du tooling Gradle client-serveur — prompt compagnon « Tooling
Gradle (client-serveur) », sections 3 et 9 (étape G1). ADR 0039.

## Périmètre livré (étape G1, v0.26.0)

- **Framing `FrameCodec`** (§3.1) : 4 octets de longueur gros-boutistes +
  payload JSON. Rejet des frames annoncées au-delà de `MAX_FRAME_SIZE`
  (16 Mo — garde DoS mémoire) **avant allocation**, frames tronquées et
  longueur invalide typées (`FrameTropGrandeException`,
  `FrameTronqueeException`, `FrameInvalideException`), EOF propre
  distinguée de la troncature (le dispatcher traite la déconnexion).
  Agnostique du contenu : un format binaire partiel restera possible
  sans toucher au framing.
- **Catalogue de messages** (§3.2) : 9 requêtes (hello, build, sync,
  tasks, dependencies, model, cancel, heap, ping) et 15 événements
  (hello, build started/output, task started/finished, build finished,
  progress, sync/partial sync, tasks/dependencies, heap, diagnostic,
  pong, erreur) — `ErrorResponse` porte un `ErrorCode` **typé**, jamais
  une chaîne libre.
- **`ProtocolJson`** (§3.2/§3.3) : discriminant de type natif
  (`@SerialName` + `classDiscriminator = "type"`), compatibilité
  ascendante (`ignoreUnknownKeys` — un client v2.1 dialogue avec un
  serveur v2.0), `encodeDefaults` pour un format câble stable.
- **Constantes `GradleProtocol`** (§3.4) : version 2, nom du socket,
  heartbeat 5 s / timeout 15 s, connexion 10 s, reconnexions 5, JAR
  `gradle-server.jar`, taille max de frame.
- **Fichiers dorés** (`src/test/resources/golden/*.json`, un par
  message) : le format câble est figé — tout renommage, retrait ou
  changement de type d'un champ fait échouer le test d'adéquation.

## Tests (bloquants avant tooling:server/client — §3)

Round-trip de sérialisation des 24 messages, décodage depuis les
fichiers dorés, adéquation de l'encodage aux dorés (comparaison
sémantique), champ inconnu ignoré, négociation de version au handshake,
erreurs typées ; framing : frame valide, consécutives, trop grande,
tronquée (en-tête et payload), longueur nulle/négative, borne exacte
16 Mo, payload vide refusé, EOF propre.

## Dépendances

`kotlinx-serialization-json` uniquement — aucune dépendance interne au
projet (règle §2.2 du prompt, vérifiée par `checkModuleDependencies`).
