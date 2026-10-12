#!/usr/bin/env bash
# vendor-grammaire-kotlin.sh — re-sync du vendoring de la grammaire Kotlin
# dans :lsp:kotlin (mission « LSP Kotlin », ADR 0115).
#
# Procédure complète (cf. lsp/kotlin/src/main/vendor/VENDOR.md) :
#   1. clone sparse du tag amont voulu ;
#   2. REMPLACEMENT des répertoires vendorés (jamais une fusion —
#      l'octet identique est la garantie de traçabilité) ;
#   3. suppression des entrées .flex/.skeleton (les lexers générés sont
#      commités par l'amont) ;
#   4. copie des licences ;
#   5. rappel des étapes manuelles obligatoires (VENDOR.md, syntax-api,
#      suite de parité).
#
# Usage : scripts/vendor-grammaire-kotlin.sh <tag>   (ex. v2.3.10)
set -euo pipefail

TAG="${1:-}"
if [[ -z "$TAG" ]]; then
    echo "usage: $0 <tag-amont> (ex. v2.3.10)" >&2
    exit 1
fi

RACINE="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$RACINE/lsp/kotlin/src/main/vendor"
TRAVAIL="$(mktemp -d)/kotlin-upstream"

echo "→ clone sparse de JetBrains/kotlin au tag $TAG (filtré, profondeur 1)…"
git clone --filter=blob:none --sparse --depth 1 --branch "$TAG" \
    https://github.com/JetBrains/kotlin.git "$TRAVAIL"
git -C "$TRAVAIL" sparse-checkout set compiler/multiplatform-parsing license

echo "→ remplacement des répertoires vendorés…"
rm -rf "$VENDOR/gen" "$VENDOR/org"
cp -R "$TRAVAIL/compiler/multiplatform-parsing/common/src/gen" "$VENDOR/"
cp -R "$TRAVAIL/compiler/multiplatform-parsing/common/src/org" "$VENDOR/"
find "$VENDOR" -name '*.flex' -o -name '*.skeleton' | xargs -r rm -f
cp "$TRAVAIL/license/LICENSE.txt" "$VENDOR/"
cp "$TRAVAIL/license/COPYRIGHT.txt" "$VENDOR/"

COMMIT="$(git -C "$TRAVAIL" rev-parse "$TAG")"
echo "→ tag $TAG = commit $COMMIT"

echo ""
echo "Étapes MANUELLES obligatoires ensuite :"
echo "  1. Mettre à jour tag + commit + date dans $VENDOR/VENDOR.md."
echo "  2. Vérifier la version de syntax-api épinglée par ce tag dans le"
echo "     libs.versions.toml amont (org-jetbrains-syntax-api) et faire"
echo "     correspondre gradle/libs.versions.toml (jetbrains-syntax-api)."
echo "  3. ./gradlew :lsp:kotlin:test — la baseline de parité DOIT être"
echo "     mise à jour délibérément (-Dparite.majBase=true) si le tag"
echo "     change la grammaire : le diff fait partie de la revue."
echo "  4. ./gradlew :lsp:kotlin:check :lsp:kotlin:rapportTaille"
echo ""
echo "⚠ Le tag v2.3.0 est celui validé par l'ADR 0115 (bootstrap 2.2.x ="
echo "  notre outillage 2.2.10). Un tag plus récent peut exiger une montée"
echo "  de chaîne d'outil — à étudier avant de commit."
