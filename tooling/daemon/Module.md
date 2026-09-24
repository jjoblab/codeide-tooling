# tooling:daemon

Daemon du tooling Gradle (prompt compagnon Tooling, section 5.4) :
`DaemonManager` porte le cycle de vie complet de l'orchestrateur JVM —
déploiement du JAR depuis les assets (`JarDeployer` à marqueur de version
SHA-256), écoute du socket **avant** le lancement (déléguée à l'hôte de
`tooling:client`), lancement du process via le port `NativeProcessLauncher`
(**jamais redéfini** — l'implémentation `core:bootstrap` construit
l'environnement canonique), handshake au secret frais, health check
ping/pong (5 s / 15 s), redémarrage borné (`MAX_RECONNECT_ATTEMPTS`),
réinjection du stderr du process dans le journal applicatif (tag
`gradle-server`).

Bibliothèque Android + Hilt, agrégée dans `:app`. Le VRAI orchestrateur
(`:tooling:server`) n'entre qu'en configuration de **test** (bout-en-bout
§7.4 — exception documentée dans `ModuleRulesPlugin`, ADR 0042).

Statut : étape G4 (v0.29.0) livrée — voir le README du module, l'ADR 0042
et `docs/TOOLING.md`.
