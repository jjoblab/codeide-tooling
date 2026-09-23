#!/usr/bin/env bash
# CodeIDE — vérifie qu'une archive de livraison est saine et autonome (section 9.2).
# Usage : scripts/verify-archive.sh <CodeIDE-vX.Y.Z-etapeNN.zip>
#   1. unzip -t : intégrité de l'archive ;
#   2. absence de fichiers interdits (build/, .gradle/, local.properties, dist/…) ;
#   3. extraction dans un répertoire temporaire puis ./gradlew assembleDebug
#      depuis cette copie : l'archive doit se suffire à elle-même.

set -euo pipefail

# Comparaisons d'octets (liste de fichiers zip incluant des noms d'objets git
# non UTF-8) : sans ceci, grep -x peut échouer selon la locale.
export LC_ALL=C

# ---------------------------------------------------------------------------
# Paramètres.
# ---------------------------------------------------------------------------
archive="${1:-}"
[ -n "$archive" ] && [ -f "$archive" ] || {
    echo "Usage : $0 <CodeIDE-vX.Y.Z-etapeNN.zip>" >&2
    exit 1
}

fichier_version="version.properties"
sdk_dir=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | head -1)
[ -n "$sdk_dir" ] || { echo "ERREUR : local.properties requis pour régénérer le sdk.dir de test" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Intégrité.
# ---------------------------------------------------------------------------
echo "[verify-archive] test d'intégrité (unzip -t)…"
unzip -tq "$archive" >/dev/null
echo "[verify-archive] archive intègre"

# ---------------------------------------------------------------------------
# 2. Fichiers interdits (section 9.3 : exclusions).
# ---------------------------------------------------------------------------
echo "[verify-archive] contrôle des exclusions…"
# ---------------------------------------------------------------------------
# Listing capturé une fois (NB : ne pas sonder `unzip` via un pipe vers
# `grep -q` — grep sort à la première correspondance, unzip reçoit SIGPIPE et
# pipefail fait échouer le pipeline de façon non déterministe).
# ---------------------------------------------------------------------------
listing=$(unzip -Z1 "$archive")

if [ -n "$(printf '%s\n' "$listing" | grep -E '(^|/)(build|\.gradle|\.idea|\.kotlin|dist|captures|\.cxx)/|(^|/)local\.properties$|\.iml$|\.keystore$|\.jks$|\.DS_Store$' || true)" ]; then
    echo "ERREUR : fichiers interdits dans l'archive :" >&2
    printf '%s\n' "$listing" | grep -E '(^|/)(build|\.gradle|\.idea|\.kotlin|dist|captures|\.cxx)/|(^|/)local\.properties$|\.iml$|\.keystore$|\.jks$|\.DS_Store$' >&2 || true
    exit 1
fi
echo "[verify-archive] aucun fichier interdit"

# ---------------------------------------------------------------------------
# 3. Contenu obligatoire (wrapper, build-logic, sources).
# ---------------------------------------------------------------------------
echo "[verify-archive] contrôle du contenu obligatoire…"
for obligatoire in gradlew gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties build-logic/build.gradle.kts settings.gradle.kts version.properties; do
    if ! printf '%s\n' "$listing" | grep -x "$obligatoire" >/dev/null; then
        echo "ERREUR : $obligatoire manquant dans l'archive" >&2
        exit 1
    fi
done
echo "[verify-archive] contenu obligatoire présent"

# ---------------------------------------------------------------------------
# 4. Build autonome depuis une copie extraite.
# ---------------------------------------------------------------------------
tmp=$(mktemp -d /tmp/codeide-verify.XXXXXX)
nettoyage() { rm -rf "$tmp"; }
trap nettoyage EXIT

echo "[verify-archive] extraction de contrôle dans $tmp…"
unzip -q "$archive" -d "$tmp"

printf 'sdk.dir=%s\n' "$sdk_dir" > "$tmp/local.properties"
chmod +x "$tmp/gradlew"

# ---------------------------------------------------------------------------
# GRADLE_USER_HOME isolé mais STABLE d'une exécution à l'autre (prompt
# compagnon Vérification-1, section 2.2) : l'objectif — prouver que
# l'archive ne dépend d'aucun cache du poste — reste atteint (les caches,
# le registre de démons et les distributions du répertoire personnel de
# l'utilisateur ne sont jamais lus : autre racine, autre registre), sans
# démarrage JVM à froid à chaque étape : le daemon amorcé à la première
# exécution est réutilisé tant qu'il vit, dans ce même répertoire isolé.
# Installé hors du dépôt : ni suivi par git, ni effacé par un `clean`.
# CODEIDE_VERIFY_GRADLE_HOME permet de le relocaliser.
# ---------------------------------------------------------------------------
maison_gradle="${CODEIDE_VERIFY_GRADLE_HOME:-$(cd "$(dirname "$0")/.." && pwd)/../verify-gradle-home}"
mkdir -p "$maison_gradle"

echo "[verify-archive] build assembleDebug depuis la copie extraite (home Gradle isolé : $maison_gradle)…"
( cd "$tmp" && GRADLE_USER_HOME="$maison_gradle" ./gradlew assembleDebug --console=plain -q )

apk="$tmp/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$apk" ] || { echo "ERREUR : APK non produit depuis l'archive" >&2; exit 1; }

echo "[verify-archive] SUCCÈS : l'archive est saine et autonome"
