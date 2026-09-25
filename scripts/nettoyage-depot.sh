#!/usr/bin/env bash
# CodeIDE — nettoyage du dépôt de travail (v0.32.5, ADR 0056 décision 6).
#
# Retire de la copie locale les fichiers qu'aucune archive de livraison ne
# porte (mêmes exclusions que scripts/package.sh, section 9.3 du prompt
# maître) : sorties de build, caches Gradle/Kotlin, fichiers IDE, captures,
# artefacts locaux. Le dépôt redevient « sain » — comparable octet près à
# l'archive générée côté serveur.
#
# Usage :
#   scripts/nettoyage-depot.sh                # simulation (rien n'est effacé)
#   scripts/nettoyage-depot.sh --appliquer    # efface réellement
#   scripts/nettoyage-depot.sh --appliquer --avec-local-properties
#                                             # efface AUSSI local.properties
#
# Sécurité :
#   - refuse de s'exécuter hors d'une racine de dépôt CodeIDE (settings.gradle.kts)
#   - ne JAMAIS toucher .git/ (l'historique EST le dépôt)
#   - simulation par défaut : affiche tout ce qui serait effacé, avec la taille
#
# local.properties est régénéré par Android Studio au prochain import du
# projet (sdk.dir) — il est donc exclu par défaut de l'effacement et proposé
# par le drapeau dédié.

set -euo pipefail

mode="simulation"
effacer_local_properties="non"

for argument in "$@"; do
    case "$argument" in
        --appliquer) mode="application" ;;
        --avec-local-properties) effacer_local_properties="oui" ;;
        *)
            echo "Argument inconnu : $argument" >&2
            echo "Usage : $0 [--appliquer] [--avec-local-properties]" >&2
            exit 1
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Garde-fous.
# ---------------------------------------------------------------------------
if [ ! -f settings.gradle.kts ] || [ ! -f version.properties ]; then
    echo "ERREUR : lancez ce script depuis la racine du dépôt CodeIDE" >&2
    echo "         (settings.gradle.kts et version.properties attendus)" >&2
    exit 1
fi
if [ ! -d .git ]; then
    echo "ERREUR : pas de .git ici — ce script nettoie un dépôt cloné," >&2
    echo "         pas un dossier arbitraire" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Cibles : répertoires entiers (relatifs à la racine, trouvés à toute
# profondeur), puis fichiers épars.
# ---------------------------------------------------------------------------
repertoires=(
    "build"              # sorties de la racine et de chaque module
    ".gradle"            # caches Gradle locaux
    ".kotlin"            # caches Kotlin/Gradle 9
    ".idea"              # fichiers Android Studio/IntelliJ
    "captures"           # captures de layout inspector
    ".cxx"               # builds NDK éventuels
    "dist"               # livrables générés localement (APK/archives)
)

fichiers_epars=(
    "*.iml"              # modules IDE à l'ancienne
    ".DS_Store"          # macOS
    "local.properties"   # trajet SDK local (exclu des archives)
)

# ---------------------------------------------------------------------------
# Collecte (find sous la profondeur des exclusions git — jamais .git).
# ---------------------------------------------------------------------------
cibles=()

for repertoire in "${repertoires[@]}"; do
    while IFS= read -r -d '' trouve; do
        cibles+=("$trouve")
    done < <(find . -path ./.git -prune -o -type d -name "$repertoire" -print0 2>/dev/null)
done

for motif in "${fichiers_epars[@]}"; do
    while IFS= read -r -d '' trouve; do
        cibles+=("$trouve")
    done < <(find . -path ./.git -prune -o -type f -name "$motif" -print0 2>/dev/null)
done

# local.properties : exclu sauf drapeau explicite.
if [ "$effacer_local_properties" != "oui" ]; then
    cibles_filtrees=()
    for cible in "${cibles[@]:-}"; do
        [ -z "$cible" ] && continue
        case "$cible" in
            ./local.properties)
                echo "  (conservé)    ./local.properties  — drapeau --avec-local-properties pour l'effacer"
                ;;
            *) cibles_filtrees+=("$cible") ;;
        esac
    done
    cibles=("${cibles_filtrees[@]:-}")
fi

# ---------------------------------------------------------------------------
# Affichage + total.
# ---------------------------------------------------------------------------
if [ "${#cibles[@]}" -eq 0 ] || [ -z "${cibles[0]:-}" ]; then
    echo "[nettoyage] rien à retirer — le dépôt est déjà sain."
    echo "[nettoyage] reste git non suivi :"
    git clean -nXd | sed 's/^/    /' || true
    exit 0
fi

total_octets=0
for cible in "${cibles[@]}"; do
    [ -z "$cible" ] && continue
    taille=$(
        du -sh "$cible" 2>/dev/null | cut -f1
    )
    printf '  %8s  %s\n' "$taille" "$cible"
    taille_octets=$(du -sb "$cible" 2>/dev/null | cut -f1 || echo 0)
    total_octets=$((total_octets + taille_octets))
done

printf '\n[nettoyage] %d cible(s), %s au total\n' "${#cibles[@]}" "$(du -sh <<<"${total_octets}0" 2>/dev/null | cut -f1 || echo "${total_octets} octets")"

if [ "$mode" = "simulation" ]; then
    echo "[nettoyage] SIMULATION — relancez avec --appliquer pour effacer."
    exit 0
fi

# ---------------------------------------------------------------------------
# Application.
# ---------------------------------------------------------------------------
echo "[nettoyage] effacement…"
for cible in "${cibles[@]}"; do
    [ -z "$cible" ] && continue
    rm -rf "$cible"
done

echo "[nettoyage] dépôt nettoyé. Reste git non suivi (git status) :"
git status --short | sed 's/^/    /' || true
git clean -nXd | sed 's/^/    /' || true
echo "[nettoyage] l'arbre suit maintenant les mêmes exclusions que l'archive de livraison."
