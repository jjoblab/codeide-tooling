#!/usr/bin/env python3
"""CodeIDE — extraction des chemins de jars depuis l'arbre Gradle.

Lit le rapport `:app:dependencies --configuration debugCompileClasspath`
et localise chaque coordonnée dans le cache modules-2 de Gradle
(`~/.gradle/caches/modules-2/files-2.1/<groupe>/<artefact>/<version>/<sha1>/<fichier>`).

Sortie : chemins absolus, une par ligne (manifeste du banc lsp:index).
"""

import os
import re
import sys

CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")

# Une ligne d'arbre : « +--- androidx.core:core-ktx:1.15.0 » ou
# « |    \\--- org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0(*) »
COORDONNEE = re.compile(r"[+\\]--- ([\w.\-]+:[\w.\-]+):([\w.\-]+)")


def localiser(groupe: str, artefact: str, version: str):
    """Chemin du jar de la coordonnée dans le cache (ou None)."""
    base = os.path.join(CACHE, groupe, artefact, version)
    if not os.path.isdir(base):
        return None
    for sha1 in sorted(os.listdir(base)):
        dossier = os.path.join(base, sha1)
        for fichier in sorted(os.listdir(dossier)):
            if fichier.endswith(".jar") and not fichier.endswith("-sources.jar"):
                return os.path.join(dossier, fichier)
    return None


def principal() -> None:
    chemins = []
    with open(sys.argv[1], encoding="utf-8") as arbre:
        for ligne in arbre:
            trouvaille = COORDONNEE.search(ligne)
            if not trouvaille:
                continue
            groupe, artefact = trouvaille.group(1).split(":")
            version = trouvaille.group(2)
            # Les versions de projet/locales ne vivent pas dans le cache.
            if version in ("project", "local") or version.endswith("SNAPSHOT"):
                continue
            chemin = localiser(groupe, artefact, version)
            if chemin:
                chemins.append(chemin)
    for chemin in chemins:
        print(chemin)


if __name__ == "__main__":
    principal()
