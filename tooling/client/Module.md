# tooling:client

Client Android du tooling Gradle (prompt compagnon Tooling, sections 5.1 à
5.3) : l'app EST le serveur du socket Unix — l'écoute s'ouvre AVANT le
lancement du process JVM (élimine tout fichier de découverte et tout
polling), le handshake au secret valide TOUTE connexion (§4.4), et la
façade publique `GradleToolingRepository` vit dans `core:domain`.

Bibliothèque Android + Hilt. Diffusion **jamais conflatée** de la sortie des
builds : canaux bornés 4096 à envoi suspendant (§5.2). Le lancement du
process orchestrateur appartient à `tooling:daemon` (G4) — ce module ne
fait que parler le protocole.

Statut : étape G3 (v0.28.0) livrée — voir le README du module, l'ADR 0041
et `docs/TOOLING.md`.
