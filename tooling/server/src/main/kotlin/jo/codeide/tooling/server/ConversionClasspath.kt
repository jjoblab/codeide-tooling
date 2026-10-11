package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule

/**
 * Conversion DTO → protocole du classpath (v8) — LA même pour le [CacheSync]
 * et le [ClasspathHandler] (l'ancien doublon « volontaire » de la v4 est
 * fermé : la conversion IdeaProject vit dans [ModelesSync.versClasspathDto],
 * la conversion Android dans [ModelesAndroidV2.convertir], la traduction
 * finale ici). Les statistiques par module (v0.40.1 §4) sont pré-calculées
 * à CE niveau : l'UI affiche les sous-lignes
 * « :app · 312 jars · 4 sources · variante debug » sans re-parcourir.
 */
internal fun ModuleClasspathDto.versClasspathModule(): ClasspathModule =
    ClasspathModule(
        name = name,
        sourceDirs = sourceDirs,
        entries =
            entries.map { entree ->
                ClasspathEntry(
                    path = entree.path,
                    kind = entreeKindVersProtocole(entree.kind),
                    scope = entree.scope,
                    sources = entree.sources,
                    aar = entree.aar,
                )
            },
        sourceDirsTest = sourceDirsTest,
        nbJars = entries.count { it.kind == "JAR" },
        // v8 : AARs normalisés (entrées JAR avec provenance `aar`) — on
        // compte les AARs d'origine DISTINCTS, plus les AAR bruts du
        // modèle idea (périphériques).
        nbAars = entries.mapNotNull { it.aar }.distinct().size + entries.count { it.kind == "AAR" },
        nbSources = sourceDirs.size,
        varianteAndroid = varianteAndroid,
        nbDependancesProjet = entries.count { it.kind == "MODULE" },
        fichiersGeneres = fichiersGeneres,
        androidJar = androidJar,
        ignore = ignore,
        raisonIgnore = raisonIgnore,
        avertissements = avertissements,
    )

/** Traduit une nature d'entrée DTO vers le protocole. */
private fun entreeKindVersProtocole(kind: String): ClasspathKind =
    when (kind) {
        "MODULE" -> ClasspathKind.MODULE
        "DOSSIER" -> ClasspathKind.DOSSIER
        "AAR" -> ClasspathKind.AAR
        else -> ClasspathKind.JAR
    }
