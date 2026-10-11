#!/usr/bin/env bash
# CodeIDE — recette VERSIONNÉE des jeux du banc lsp:index (ADR 0111 :
# « banc avant/après sur les trois jeux », reconstitution reproductible).
#
# Jeux (docs/LSP_JAVA.md §12 — compositions calquées sur l'étude) :
#  - petit  : android.jar + 19 jars androidx du moyen (20 entrées) ;
#  - moyen  : classpath debugCompileClasspath RÉEL de :app (≈73 entrées) ;
#  - lourd  : projet Compose synthétique résolu par Gradle (≈250 entrées).
#  - extra  : jar hors manifestes (test « +1 dépendance »).
#
# Sortie : build/lsp-index/jeux/{petit,moyen,lourd}/manifeste.txt (chemins
# absolus, une entrée par ligne) + extra/extra-banc.jar.
#
# Usage : scripts/lsp-index/construire-jeux.sh [jeu]   (sans argument : tous)

set -euo pipefail

# Environnement de build (JDK Temurin, SDK Android) — cf. scripts/env.sh.
export JAVA_HOME="${JAVA_HOME:-/home/z/tools/jdk-21}"
export ANDROID_HOME="${ANDROID_HOME:-/home/z/android-sdk}"

RACINE="$(cd "$(dirname "$0")/../.." && pwd)"
PROVISION="$RACINE/build/lsp-index/provision"
JEUX="$RACINE/build/lsp-index/jeux"
GRADLE="$RACINE/gradlew"

mkdir -p "$PROVISION" "$JEUX"

log() { printf '[jeux] %s\n' "$*"; }

# --- moyen : classpath réel de :app (rapport + cache modules-2) -------
construire_moyen() {
    log "moyen : résolution du classpath debug de :app…"
    cd "$RACINE"
    "$GRADLE" -q :app:dependencies --configuration debugCompileClasspath > "$PROVISION/moyen-arbre.txt"
    python3 "$RACINE/scripts/lsp-index/jeux-depuis-arbre.py" \
        "$PROVISION/moyen-arbre.txt" > "$PROVISION/moyen-chemins.txt"
    mkdir -p "$JEUX/moyen"
    cp "$PROVISION/moyen-chemins.txt" "$JEUX/moyen/manifeste.txt"
    log "moyen : $(wc -l < "$JEUX/moyen/manifeste.txt") entrées"
}

# --- petit : android.jar + 19 premiers jars androidx du moyen --------
construire_petit() {
    log "petit : android.jar + 19 jars androidx…"
    mkdir -p "$JEUX/petit"
    ANDROID_JAR="$ANDROID_HOME/platforms/android-37.2/android.jar"
    if [[ ! -f "$ANDROID_JAR" ]]; then
        log "ERREUR : $ANDROID_JAR absent — installer le SDK (scripts/setup-env.sh)"
        exit 1
    fi
    {
        echo "$ANDROID_JAR"
        grep "androidx" "$JEUX/moyen/manifeste.txt" | head -19
    } > "$JEUX/petit/manifeste.txt"
    log "petit : $(wc -l < "$JEUX/petit/manifeste.txt") entrées"
}

# --- lourd : mélange réel du cache Gradle (jars + AAR, déterministe) --
# L'étude §12 résolvait un projet Compose ; la RECETTE versionnée retient
# le résultat équivalent : les ~250 premiers artefacts (jar + AAR, tri
# déterministe par chemin) du cache — ~76 % d'AAR comme l'étude, et le
# banc exerce la normalisation AAR d'ADR 0111 §10.
construire_lourd() {
    log "lourd : balayage déterministe du cache Gradle…"
    mkdir -p "$JEUX/lourd"
    python3 - "$HOME/.gradle/caches/modules-2/files-2.1" "$JEUX/lourd/manifeste.txt" <<'PYJEUX'
import os
import sys

racine, sortie = sys.argv[1], sys.argv[2]
entrees = []
for dossier, _, fichiers in os.walk(racine):
    for nom in fichiers:
        if nom.endswith(".aar") or (nom.endswith(".jar") and "-sources" not in nom and "-javadoc" not in nom):
            entrees.append(os.path.join(dossier, nom))
entrees.sort()
with open(sortie, "w", encoding="utf-8") as flux:
    for chemin in entrees[:250]:
        flux.write(chemin + chr(10))
print(len(entrees[:250]))
PYJEUX
    log "lourd : $(wc -l < "$JEUX/lourd/manifeste.txt") entrées"
}

# --- extra : jar hors manifestes (+1 dépendance du banc) --------------
construire_extra() {
    log "extra : copie d'un jar du cache sous nouvelle identité…"
    mkdir -p "$JEUX/extra"
    SOURCE=$(grep -m1 "modules-2" "$JEUX/moyen/manifeste.txt" || true)
    if [[ -z "$SOURCE" ]]; then
        SOURCE=$(head -1 "$JEUX/moyen/manifeste.txt")
    fi
    cp "$SOURCE" "$JEUX/extra/extra-banc.jar"
    log "extra : $JEUX/extra/extra-banc.jar"
}

case "${1:-tous}" in
    moyen) construire_moyen ;;
    petit) construire_petit ;;
    lourd) construire_lourd ;;
    extra) construire_extra ;;
    tous)
        construire_moyen
        construire_petit
        construire_lourd
        construire_extra
        ;;
    *) echo "usage : $0 [petit|moyen|lourd|extra|tous]" >&2; exit 1 ;;
esac

log "terminé — manifestes dans $JEUX"
