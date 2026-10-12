// CodeIDE — paramètres du build multi-modules.
// Modules de la section 5.1 du prompt maître (18 à la fin de la Phase 1) ;
// la Phase 2 (terminal intégré, prompt compagnon Terminal-1) ajoute
// core:bootstrap puis core:terminal-runtime et feature:terminal.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Bibliothèque d'édition code-editor (prompt compagnon, section 2.2) :
        // JitPack pour un build autonome sans jeton — GitHub Packages resterait
        // l'alternative documentée pour un usage local.
        maven { url = uri("https://jitpack.io") }
        // Tooling Gradle (G2) : org.gradle:gradle-tooling-api ne vit que sur le
        // dépôt officiel de Gradle — les métadonnées Maven Central de cette
        // coordonnée sont périmées (7.3-snapshot de 2021). Vérifié le
        // 2026-09-24 (voir docs/TOOLING.md).
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
        // LSP Kotlin (K1, ADR 0115) : org.jetbrains:syntax-api — l'arbre de
        // construction syntaxique contre lequel la grammaire vendorée du
        // compilateur Kotlin est écrite. Pas sur Central (artefact de la
        // plateforme IntelliJ), publié sur intellij-dependencies ; accès
        // par le cache-redirector public de JetBrains (plus stable que
        // packages.jetbrains.team, même contenu). Filtre strict : rien
        // d'autre ne vient de là.
        maven {
            url = uri("https://cache-redirector.jetbrains.com/intellij-dependencies")
            content {
                // Le filtre doit admettre les coordonnées RACINES (métadonnées
                // KMP) ET leurs variantes par cible (-jvm…) — un filtre ne
                // nommant que la racine résout les métadonnées puis ne
                // trouve aucun jar. lang-syntax = arbre syntaxique KMP
                // (SyntaxNode/LeafSyntaxNode) dont syntax-api dépend.
                includeModuleByRegex("org\\.jetbrains", "syntax-api.*")
                includeModuleByRegex("org\\.jetbrains", "lang-syntax.*")
            }
        }
    }
}

// Convention plugins de build-logic (build inclus).
includeBuild("build-logic")

rootProject.name = "CodeIDE"

include(":app")

// Mission « Exécuter » R2 (ADR 0103) : bibliothèque de débogage Java pure
// injectée dans les builds debug des projets de l'utilisateur — zéro
// dépendance, zéro permission, jamais active hors debug. L'AAR est un
// artefact de build recopié vers les assets de l'app (copierAarVersAssets).
include(":applog-runtime")

// Socle transverse.
include(":core:model")
include(":core:domain")
// Mission « Cycle de vie » (prompt 3 §5.1, étape B — ADR 0114) : contrat de
// session de projet (machine à états observable) et registre de fermeture
// (Fermable : ordre inverse, délai par élément, drapeau « ne pas annuler »).
// Kotlin JVM PUR — les adaptateurs des ressources réelles vivront en
// étape C (feature:editor) et D (tooling).
include(":core:session")
include(":core:data")
include(":core:database")
include(":core:datastore")
include(":core:storage")
include(":core:logging")
include(":core:crash")
include(":core:ui")
include(":core:testing")

// Phase 2 — terminal intégré (prompt compagnon Terminal-1, section 2.1) :
// localisation des outils et environnement de sous-processus.
include(":core:bootstrap")
include(":core:terminal-runtime")

// Fonctionnalités.
include(":feature:onboarding")
include(":feature:home")
include(":feature:newproject")
include(":feature:settings")
include(":feature:diagnostics")
include(":feature:install")
include(":feature:editor")
// Terminal T5 : écran plein écran du terminal (prompt Terminal-1, section 5).
include(":feature:terminal")

// Outils (hors application — étape 9, ADR 0019 : harnais de vérification).
include(":tools:generateur")

// Phase 2 — tooling Gradle client-serveur (prompt compagnon Tooling,
// section 2.1) : G1 pose protocol et testing ; G2 ajoute api (modèles
// partagés) et server (orchestrateur Tooling API, JAR unique exécutable) ;
// G3 le client Android, G4 le daemon (déploiement du JAR, lancement du
// process, health check, relances bornées).
include(":tooling:protocol")
include(":tooling:api")
include(":tooling:server")
include(":tooling:client")
include(":tooling:daemon")
include(":tooling:testing")

// Mission « LSP Java » (prompt 2, section 7 et ADR 0108-0112) :
// :lsp:model — modèle de projet LSP en Kotlin JVM pur (étape 1 :
// conversion ClasspathResult → snapshots immuables empreintés,
// persistance versionnée validée) ; :lsp:index — index disque en
// segments par jar (étape 2, ADR 0111 : clés de contenu, fusion LSM à
// la requête, format binaire versionné, construction en arrière-plan) ;
// :lsp:java — moteur vendored Java
// (ecj patché pour ART, exemption Kotlin documentée par l'ADR 0108) ;
// :lsp:ecj-art — patcher ASM build-time (jamais dans l'APK) ;
// :lsp:testing — suite d'intégration moteur ↔ index (v0.96.3, pattern
// :tooling:testing : les tests du branchement vivent AU-DESSUS des deux
// modules — le moteur vendored ne dépend d'AUCUN module interne, test
// compris, ADR 0108, vérifié par checkModuleDependencies).
include(":lsp:model")
include(":lsp:index")
include(":lsp:java")
include(":lsp:ecj-art")
include(":lsp:testing")
// :lsp:host (étape 4, ADR 0109) : l'HÔTE — SessionLsp (transport abstrait
// D2, in-process ART par défaut), orchestrateur sync → modèle → index →
// moteurs par module (API qualifiée lot E), adaptateur production de la
// SPI IndexNoms, état d'indexation pour l'UI. Point unique de changement
// de transport.
include(":lsp:host")
// :lsp:kotlin (mission « LSP Kotlin », LSP_KOTLIN.md étape K1, ADR 0115) :
// moteur d'analyse Kotlin in-process — grammaire multiplateforme du
// compilateur Kotlin VENDORÉE (Apache 2.0, lsp/kotlin/src/main/vendor,
// zéro modification locale) + façade Kt* + suite de parité. Indépendant de
// tout module interne (pack « langue » téléchargeable différé — D6 : SPI
// ServiceLoader jo.lspkotlin.api.MoteurLangageKotlin seul visible).
include(":lsp:kotlin")
