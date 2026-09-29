#!/usr/bin/env bash
# CodeIDE — incrémente la version (section 9.1 du prompt maître).
# Usage : scripts/bump-version.sh <major|minor|patch>
#   - met à jour version.properties (VERSION_NAME et VERSION_CODE
#     = major*10000 + minor*100 + patch, strictement croissant) ;
#   - prépare une entrée de CHANGELOG.md non publiée.

set -euo pipefail

# ---------------------------------------------------------------------------
# Lecture de la version courante.
# ---------------------------------------------------------------------------
fichier_version="version.properties"
[ -f "$fichier_version" ] || { echo "ERREUR : $fichier_version introuvable" >&2; exit 1; }

version_courante=$(sed -n 's/^VERSION_NAME=//p' "$fichier_version" | head -1)
[ -n "$version_courante" ] || { echo "ERREUR : VERSION_NAME illisible" >&2; exit 1; }

IFS='.' read -r major minor patch <<<"$version_courante"

# ---------------------------------------------------------------------------
# Calcul de la nouvelle version.
# ---------------------------------------------------------------------------
palier="${1:-}"
case "$palier" in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
    *) echo "Usage : $0 <major|minor|patch>" >&2; exit 1 ;;
esac

nouvelle_version="$major.$minor.$patch"
nouveau_code=$((major * 10000 + minor * 100 + patch))
code_courant=$(sed -n 's/^VERSION_CODE=//p' "$fichier_version" | head -1)
if [ "$nouveau_code" -le "$code_courant" ]; then
    echo "ERREUR : VERSION_CODE ne décroît pas ($nouveau_code <= $code_courant)" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Écriture de version.properties.
# ---------------------------------------------------------------------------
cat > "$fichier_version" <<FIN
# Source unique de vérité pour la version de CodeIDE (section 9.1 du prompt maître).
# Lue par les convention plugins de build-logic.
# VERSION_CODE = major * 10000 + minor * 100 + patch (strictement croissant).
VERSION_NAME=$nouvelle_version
VERSION_CODE=$nouveau_code
FIN

# ---------------------------------------------------------------------------
# Préparation de l'entrée de CHANGELOG.md.
# ---------------------------------------------------------------------------
date_du_jour=$(date +%F)
# NB : le marqueur contient des crochets — dans un motif sed ils forment une
# expression de classe de caractères, et « ## [Non publié] » littéral ne
# matchait JAMAIS (le marqueur restait en place, l'entrée n'était jamais
# datée : contournement manuel à chaque livraison jusqu'à v0.37.1). Les
# crochets du MOTIF sont donc échappés ; côté REMPLACEMENT ils sont littéraux.
# Autre défaut corrigé en même temps : l'entrée était AJOUTÉE EN FIN de
# fichier, donc à la position la plus ANCIENNE d'un journal
# antéchronologique — elle est désormais insérée avant la première version
# existante (la plus récente).
marqueur="## [Non publié]"
if [ -f CHANGELOG.md ] && ! grep -q "^## \[$nouvelle_version\]" CHANGELOG.md; then
    # NB : le grep est ANCRÉ (^) — le journal documente ses propres
    # correctifs et cite le marqueur DANS LA PROSE (`s|^## [Non publié]|…|`)
    # ; un grep non ancré (même -F) matche cette prose et détourne le
    # script vers la branche « marqueur ouvert » (rien inséré, message de
    # succès mensonger — découvert à la livraison 0.39.0).
    if grep -q "^## \[Non publié\]" CHANGELOG.md; then
        # Marqueur [Non publié] déjà ouvert : le dater en place.
        sed -i "s|^## \[Non publié\]|## [$nouvelle_version] – $date_du_jour|" CHANGELOG.md
    elif grep -q "^## \[" CHANGELOG.md; then
        # Journal existant (antéchronologique) : insérer l'entrée vierge
        # AVANT la première version journalisée.
        awk -v version="$nouvelle_version" -v date="$date_du_jour" '
            !insere && /^## \[/ {
                printf "## [%s] – %s\n\n### Ajouté\n\n- (à compléter)\n\n", version, date
                insere = 1
            }
            { print }
        ' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md
    else
        # Aucune version encore journalisée : créer la première entrée.
        printf '\n## [%s] – %s\n\n### Ajouté\n\n- (à compléter)\n' "$nouvelle_version" "$date_du_jour" >> CHANGELOG.md
    fi
fi

echo "version : $version_courante → $nouvelle_version (VERSION_CODE $code_courant → $nouveau_code)"
echo "CHANGELOG.md : entrée ouverte pour $nouvelle_version — complétez-la avant la livraison."
