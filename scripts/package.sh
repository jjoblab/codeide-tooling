#!/usr/bin/env bash
# CodeIDE — produit les livrables de fin d'étape (section 9.2 du prompt maître).
# Usage : scripts/package.sh < numéro_étape >
#   dist/CodeIDE-vX.Y.Z-etapeNN.zip    archive complète du projet
#   dist/CodeIDE-vX.Y.Z-debug.apk      APK debug
#   dist/SHA256SUMS                    empreintes

set -euo pipefail

# ---------------------------------------------------------------------------
# Paramètres.
# ---------------------------------------------------------------------------
etape="${1:-}"
case "$etape" in
    ''|*[!0-9]*) echo "Usage : $0 <numéro_étape>" >&2; exit 1 ;;
esac
etape_formatee=$(printf 'etape%02d' "$etape")

fichier_version="version.properties"
version=$(sed -n 's/^VERSION_NAME=//p' "$fichier_version" | head -1)
[ -n "$version" ] || { echo "ERREUR : VERSION_NAME illisible" >&2; exit 1; }

archive="CodeIDE-v${version}-${etape_formatee}.zip"
apk="CodeIDE-v${version}-debug.apk"

# ---------------------------------------------------------------------------
# Préparation.
# ---------------------------------------------------------------------------
mkdir -p dist
if [ -e "dist/$archive" ]; then
    echo "ERREUR : dist/$archive existe déjà — une archive n'est jamais écrasée (section 9.3)" >&2
    exit 1
fi

echo "[package] version $version, $etape_formatee"

# ---------------------------------------------------------------------------
# 1. APK debug.
# ---------------------------------------------------------------------------
echo "[package] construction de l'APK debug…"
./gradlew :app:assembleDebug --console=plain -q
cp app/build/outputs/apk/debug/app-debug.apk "dist/$apk"

# ---------------------------------------------------------------------------
# 2. Archive du projet (inclusions/exclusions de la section 9.3).
# ---------------------------------------------------------------------------
echo "[package] création de $archive…"
zip -qr "dist/$archive" . \
    -x 'dist/*' \
    -x 'build/*' -x '*/build/*' -x '*/*/build/*' -x '*/*/*/build/*' \
    -x '.gradle/*' -x '*/.gradle/*' \
    -x '.kotlin/*' -x '*/.kotlin/*' \
    -x '.idea/*' -x '*/.idea/*' \
    -x 'local.properties' \
    -x 'captures/*' \
    -x '.cxx/*' \
    -x '*.iml' \
    -x '.DS_Store' \
    -x 'app/src/main/assets/tooling/*'

# ---------------------------------------------------------------------------
# 3. Empreintes SHA-256.
# ---------------------------------------------------------------------------
echo "[package] calcul des empreintes…"
( cd dist && sha256sum "$archive" "$apk" > SHA256SUMS )

echo "[package] livrables :"
ls -lh "dist/$archive" "dist/$apk" dist/SHA256SUMS | awk '{print "  " $NF " (" $5 ")"}'
