#!/usr/bin/env bash
# CodeIDE — Kit de mesures de référence « Cycle de vie » (§4.5 de
# docs/CYCLE_DE_VIE.md, mission 3 du prompt 3).
#
# Exécute les scénarios S1–S7 du protocole de BASELINE sur l'appareil du
# propriétaire, avec capture automatique de tout ce qui se mesure par
# adb (journaux filtrés sur les tags RÉELS du code, meminfo, ps), et
# guide à la main le reste (gestes UI, observation LeakCanary,
# chronométrage vidéo des frappes pour le p95 LSP de §11.2 de
# docs/LSP_JAVA.md).
#
# Usage :
#   ./scripts/baseline-cycle-vie.sh [--apk <chemin/app-debug.apk>]
#                                   [--paquet jo.codeide]
#                                   [--serial <id-adb>]
#
# Préparation recommandée (état pré-correctif FIGÉ, cf. §2 du doc) :
#   git checkout v0.103.0 && ./gradlew :app:assembleDebug
#   ./scripts/baseline-cycle-vie.sh --apk app/build/outputs/apk/debug/app-debug.apk
# (sans --apk, le kit utilise le paquet déjà installé sur l'appareil).
#
# Sortie : resultats-baseline-<AAAAMMJJ-HHMM>/ (journaux, relevés
# meminfo/ps, vidéo des frappes si consentie, rapport.md assemblé).
#
# Conditions du protocole (§4.5) : build DEBUG (LeakCanary actif),
# projet réel multi-gradle (P) et un second (Q), 10 min de repos avant
# le premier relevé, écran allumé sauf S6.

set -euo pipefail

# ---------------------------------------------------------------------------
# Paramètres.
# ---------------------------------------------------------------------------
PAQUET="jo.codeide"
APK=""
SERIAL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk) APK="${2:?--apk exige un chemin}"; shift 2 ;;
        --paquet) PAQUET="${2:?--paquet exige un nom}"; shift 2 ;;
        --serial) SERIAL="${2:?--serial exige un id}"; shift 2 ;;
        *) echo "Option inconnue : $1" >&2; exit 1 ;;
    esac
done

# adb (optionnellement ciblé sur un appareil).
adb() {
    if [[ -n "$SERIAL" ]]; then
        command adb -s "$SERIAL" "$@"
    else
        command adb "$@"
    fi
}

# ---------------------------------------------------------------------------
# Pré-vol : adb, appareil, paquet (ou APK fourni).
# ---------------------------------------------------------------------------
command -v adb > /dev/null || {
    echo "ERREUR : adb introuvable dans PATH (platform-tools du SDK Android)." >&2
    exit 1
}
appareils=$(adb devices | awk 'NR > 1 && $2 == "device" {print $1}' | wc -l)
[[ "$appareils" -ge 1 ]] || {
    echo "ERREUR : aucun appareil connecté (adb devices)." >&2
    exit 1
}
[[ "$appareils" -eq 1 || -n "$SERIAL" ]] || {
    echo "ERREUR : plusieurs appareils — précisez --serial." >&2
    adb devices
    exit 1
}
if [[ -n "$APK" ]]; then
    echo "== Installation de l'APK de baseline : $APK"
    adb install -r "$APK"
elif ! adb shell pm list packages | grep -q "package:$PAQUET"; then
    echo "ERREUR : $PAQUET n'est pas installé — fournissez --apk ou installez-le." >&2
    exit 1
fi
paquet_present=$(adb shell pidof "$PAQUET" || true)

# ---------------------------------------------------------------------------
# Répertoire de résultats + relevés communs.
# ---------------------------------------------------------------------------
HORODATE=$(date +%Y%m%d-%H%M)
SORTIE="resultats-baseline-$HORODATE"
mkdir -p "$SORTIE"

# Journal : les tags RÉELS du code (v0.102→v0.105) — attention, le §4.5
# historique citait LspEditeurSession/GradleServer qui N'EXISTENT PAS :
# le cycle de session LSP vit sous « Editor », le serveur sous
# « gradle-server » (minuscule, réinjecté par DaemonManager).
TAGS="App|MainActivity|Editor|LspEditeur|Navigation|ToolingDaemon|gradle-server|ToolingService|ToolingClient|TerminalService|install|LeakCanary|StrictMode"

journal_purger() { adb logcat -c 2> /dev/null || true; }

journal_tuer() {
    [[ -n "${PID_LOGCAT:-}" ]] && kill "$PID_LOGCAT" 2> /dev/null || true
    PID_LOGCAT=""
}

journal_demarrer() {
    journal_tuer
    adb logcat -v time > "$SORTIE/$1.log" 2> /dev/null &
    PID_LOGCAT=$!
}

# Relevé meminfo : total PSS, tas Java, tas natif.
meminfo() {
    local etiquette="$1"
    adb shell dumpsys meminfo "$PAQUET" > "$SORTIE/meminfo-$etiquette.txt"
    awk '/^ *TOTAL PSS:/ || /^ *TOTAL:/ {print "TOTAL_PSS_KB="$2}
         /Java Heap:/ {print "JAVA_HEAP_KB="$2}
         /Native Heap:/ {print "NATIVE_HEAP_KB="$2}' \
        "$SORTIE/meminfo-$etiquette.txt" >> "$SORTIE/meminfo-resume.tsv" || true
    echo -e "meminfo\t$etiquette\t$(grep -m1 '^ *TOTAL PSS:' "$SORTIE/meminfo-$etiquette.txt" | awk '{print $3, $4}')" \
        >> "$SORTIE/rapport-brut.tsv"
}

# Relevé processus : enfants du paquet (orchestrateur java, GradleDaemon, shells).
enfants() {
    local etiquette="$1"
    adb shell ps -A -o PID,PPID,NAME | grep -i codeide > "$SORTIE/ps-$etiquette.txt" || true
    local nb
    nb=$(grep -c . "$SORTIE/ps-$etiquette.txt" || true)
    echo -e "processus\t$etiquette\t$nb ligne(s) (dont enfants : voir ps-$etiquette.txt)" \
        >> "$SORTIE/rapport-brut.tsv"
}

# Compte d'une ligne de journal (ex. reconnexions LSP de S2).
compter_journal() {
    local motif="$1" fichier="$2"
    grep -c "$motif" "$fichier" 2> /dev/null || echo 0
}

pause() { read -r -p "$1" _; }
chrono_debut() { CHRONO=$(date +%s%3N); }
chrono_fin() {
    local ms=$(( $(date +%s%3N) - CHRONO ))
    echo -e "durée_ms\t$1\t$ms" >> "$SORTIE/rapport-brut.tsv"
}

echo "Kit de baseline §4.5 — résultats dans $SORTIE/"
: > "$SORTIE/rapport-brut.tsv"

pause "== Repos de 10 minutes avant le premier relevé (protocole §4.5).
   Laissez l'appareil au repos, écran allumé, puis appuyez sur Entrée."

# ---------------------------------------------------------------------------
# S1 — 20 cycles ouvrir/quitter (croissance visée < 5 % en étape F).
# ---------------------------------------------------------------------------
pause "S1 — 20 cycles. Pour CHAQUE palier (1, 5, 10, 20) : rouvrez P
   depuis l'accueil, attendez la FIN de l'indexation (disque vert),
   quittez par retour SANS onglet sale, puis appuyez sur Entrée.
   (LeakCanary doit rester muet — toute rétention est une anomalie.)"
journal_demarrer "s1"
for palier in 1 5 10 20; do
    pause "   >>> Effectuez le cycle n°$palier (ouvrir → indexation finie → quitter), puis Entrée."
    meminfo "s1-cycle$palier"
    enfants "s1-cycle$palier"
done
journal_tuer

# ---------------------------------------------------------------------------
# S2 — rotation ×5 (temps de rebranchement LSP au journal).
# ---------------------------------------------------------------------------
pause "S2 — rotation. Ouvrez P, ouvrez un onglet .java. Le kit force
   5 rotations (1 portrait → 2 paysage → …) ; gardez l'écran actif."
journal_demarrer "s2"
meminfo "s2-avant"
adb shell settings put system accelerometer_rotation 0
orientation=0
for i in 1 2 3 4 5; do
    orientation=$(( (orientation + 1) % 4 ))
    adb shell settings put system user_rotation "$orientation"
    sleep 4
    adb shell settings put system user_rotation 0
    sleep 4
done
adb shell settings put system accelerometer_rotation 1
meminfo "s2-apres"
enfants "s2"
journal_tuer
rebranchements=$(compter_journal "LSP Java connecté" "$SORTIE/s2.log")
echo -e "journal\tS2-rebranchements-LSP\t$rebranchements (attendu ≥ 5 : un par rotation)" \
    >> "$SORTIE/rapport-brut.tsv"

# ---------------------------------------------------------------------------
# S3 — P puis Q (aucun vestige de P dans Q).
# ---------------------------------------------------------------------------
pause "S3 — changement de projet. Ouvrez P, quittez, ouvrez Q,
   ouvrez un .java, puis appuyez sur Entrée."
journal_demarrer "s3"
chrono_debut
pause "   >>> Effectuez la séquence (P → quitter → Q → ouvrir .java), puis Entrée."
chrono_fin "S3-changement-projet"
enfants "s3-apres"
meminfo "s3-apres"
journal_tuer
for motif in "session de projet fermée" "session de projet refusée"; do
    echo -e "journal\tS3-$motif\t$(compter_journal "$motif" "$SORTIE/s3.log")" >> "$SORTIE/rapport-brut.tsv"
done

# ---------------------------------------------------------------------------
# S4 — build en cours → quitter (décision §2.1c : le build continue).
# ---------------------------------------------------------------------------
pause "S4 — build en vol. Dans P, lancez un build, QUITTEZ l'éditeur
   pendant « tâches en cours », observez la notification finale, puis
   rouvrez P (console : état cohérent ?). Appuyez sur Entrée quand
   c'est fait."
journal_demarrer "s4"
chrono_debut
pause "   >>> Effectuez la séquence, puis Entrée."
chrono_fin "S4-build-en-vol"
enfants "s4"
journal_tuer

# ---------------------------------------------------------------------------
# S5 — terminal → quitter (politique §2.4 : sessions GLOBALES).
# ---------------------------------------------------------------------------
pause "S5 — terminal. Ouvrez un terminal depuis l'éditeur, lancez
   « top », QUITTEZ l'éditeur (le terminal vit), observez la
   notification (42 001), puis appuyez sur Entrée."
journal_demarrer "s5"
chrono_debut
pause "   >>> Effectuez la séquence, puis Entrée."
chrono_fin "S5-terminal"
enfants "s5"
journal_tuer

# ---------------------------------------------------------------------------
# S6 — arrière-plan 10 minutes (écran ÉTEINT — seul scénario).
# ---------------------------------------------------------------------------
echo "S6 — arrière-plan. Rouvrez P (éditeur visible), le kit éteint
   l'écran 10 minutes (RIEN ne doit être coupé — baseline)."
journal_demarrer "s6"
meminfo "s6-avant"
adb shell input keyevent KEYCODE_POWER
sleep 600
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent KEYCODE_MENU
pause "   >>> L'écran est rallumé : mesurez le temps de reprise à l'œil,
   rouvrez l'application si besoin, puis appuyez sur Entrée."
meminfo "s6-apres"
enfants "s6-apres"
journal_tuer

# ---------------------------------------------------------------------------
# S7 — kill + relance (processus orphelins, restauration).
# ---------------------------------------------------------------------------
pause "S7 — mort du processus. Rouvrez P (éditeur visible), puis
   appuyez sur Entrée : le kit tuera le processus (am kill)."
journal_demarrer "s7"
enfants "s7-avant-kill"
adb shell am kill "$PAQUET"
sleep 3
enfants "s7-orphelins"
pause "   >>> Les orphelins sont relevés (ps-s7-orphelins.txt : orchestrateur
   java, shells). Relancez l'app par le lanceur, rouvrez P (onglets,
   contenus jamais sales, session nourrie depuis le cache), puis Entrée."
chrono_debut
pause "   >>> Effectuez la relance + réouverture de P, puis Entrée."
chrono_fin "S7-relance-restauration"
enfants "s7-apres"
journal_tuer

# ---------------------------------------------------------------------------
# p95 LSP (§11.2 de LSP_JAVA.md) — relevés guidés + vidéo des frappes.
# ---------------------------------------------------------------------------
pause "p95 LSP — chronométrages guidés. Pour chaque mesure, notez le
   temps resenti à la montre/vidéo, le journal horodaté fait foi :
   1) ouverture À FROID (première indexation de P) ;
   2) ouverture À CHAUD (réouverture) ;
   3) sync Gradle complète ;
   4) création d'un type puis complétion le référençant ;
   5) fermeture/réouverture de l'éditeur.
   Appuyez sur Entrée entre chaque mesure."
journal_demarrer "p95"
for mesure in ouverture-froid ouverture-chaud sync-complete completion fermeture-reouverture; do
    chrono_debut
    pause "   >>> Mesure « $mesure » : effectuez le geste, puis Entrée."
    chrono_fin "p95-$mesure"
done
journal_tuer

read -r -p "Frappes (p95 frappe, vidéo 60 i/s recommandée) : enregistrer
   l'écran pendant 20 s de frappe ? [o/N] " reponse
if [[ "${reponse:-n}" == "o" ]]; then
    adb shell screenrecord --time-limit 20 --bit-rate 12000000 /sdcard/baseline-frappe.mp4 &
    PID_VIDEO=$!
    pause "   >>> FRAPPEZ pendant 20 s dans un onglet .java (mesure à la vidéo)."
    wait "$PID_VIDEO" || true
    sleep 2
    adb pull /sdcard/baseline-frappe.mp4 "$SORTIE/frappe.mp4" || true
    adb shell rm -f /sdcard/baseline-frappe.mp4 || true
fi

# ---------------------------------------------------------------------------
# Assemblage du rapport.
# ---------------------------------------------------------------------------
{
    echo "# Baseline « Cycle de vie » — $HORODATE"
    echo
    echo "Paquet : $PAQUET — APK : ${APK:-déjà installé (préciser la version !)}"
    echo "Scénarios S1–S7 du protocole §4.5 + p95 LSP (§11.2 de LSP_JAVA.md)."
    echo
    echo "## Relevés bruts (meminfo TOTAL PSS en ko, ps = processus liés au paquet)"
    column -t -s $'\t' "$SORTIE/rapport-brut.tsv" 2> /dev/null || cat "$SORTIE/rapport-brut.tsv"
    echo
    echo "## Analyses à porter dans le tableau §4.5 du document"
    echo "- S1 : croissance du TOTAL PSS cycle 1 → 20 (visée < 5 % en F) ;"
    echo "  nombre de lignes ps CONSTANT ; rétentions LeakCanary (aucune attendue)."
    echo "- S2 : rebranchements « LSP Java connecté » = 5 (un par rotation) ;"
    echo "  tas avant/après (s2-avant/s2-apres) → coût cel-lsp (réarbitrage §2.5)."
    echo "- S3 : vestiges de P dans la session Q (journal s3.log) ;"
    echo "  démons Gradle de P persistants (politique — ps s3)."
    echo "- S4 : build terminé + notification finale + console cohérente."
    echo "- S5 : notification 42 001 présente, shell vivant (ps s5)."
    echo "- S6 : RIEN coupé (baseline) ; tas au retour, temps de reprise."
    echo "  → POLITIQUE DU DÉMON §2.2 et temporisation §2.3 : décider sur ces chiffres."
    echo "- S7 : orphelins au kill (ps-s7-orphelins.txt) ; restauration propre."
    echo "- p95 : reporter chaque durée + frappes (vidéo frappe.mp4 si consentie)."
    echo
    echo "## Journalisation complémentaire (procédure §4.6)"
    echo "- StrictMode : grep StrictMode *.log — consigner TOUTE violation ;"
    echo "- LeakCanary : notifications observées pendant S1 (aucune attendue)."
    echo
    echo "Fichiers : meminfo-*.txt, ps-*.txt, s*.log (journaux filtrés à posteriori"
    echo "sur : $TAGS)."
} > "$SORTIE/rapport.md"

echo "== Terminé. Rapport : $SORTIE/rapport.md"
echo "   Porter les chiffres dans docs/CYCLE_DE_VIE.md §4.5 (tableau) :"
echo "   c'est LA baseline de comparaison de l'étape F."
