# Changelog — codeide-tooling

## 1.0.0 (2026-10-17)

- Extraction de jjoblab/CodeIDE par `git filter-repo` (historique
  conservé : 315 commits touchant `tooling/`, `build-logic/`, `gradle/`
  et les fichiers de build racine).
- Périmètre : `:tooling:protocol`, `:tooling:api`, `:tooling:server`,
  `:tooling:testing`. `tooling:client` et `tooling:daemon` restent dans
  CodeIDE (dépendance `:core:domain` non encore publiée) — leur
  historique est conservé ici.
- Adaptations : `settings.gradle.kts` tooling-only (retrait du bloc
  LayoutLib composite et des dépôts app — JitPack, cache-redirector
  JetBrains), version indépendante 1.0.0, config detekt partagée
  importée, `config/signature` retirée (dépôt JVM pur).
- Vérifié : spotless + detekt + checkModuleDependencies verts, 146
  tests JVM verts, `gradle-server.jar` (7,7 Mo) construit.
