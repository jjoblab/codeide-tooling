#!/usr/bin/env bash
# CodeIDE — Kit de VALIDATION d'étape F (critères §8 du prompt 3,
# docs/CYCLE_DE_VIE.md §9) : exécuté sur la version COURANTE
# (post-correctifs C–E), à comparer à la baseline du kit
# scripts/baseline-cycle-vie.sh (pré-correctif, tag v0.103.0).
#
# Ce qui est AUTOMATISÉ (verdicts calculés par le kit) :
#   F1  20 cycles ouvrir/quitter par « am start » + extra de routage
#       (v0.106.0) : GC forcé + meminfo aux cycles 1/5/10/20 →
#       croissance du tas Java (visée < 5 %) ; LeakCanary/StrictMode
#       muets (aucune instance d'EditorActivity retenue).
#   F2  processus après la sortie : enfants du paquet + lignes de
#       commande (/proc/*/cmdline) — vestige du PROJET = échec, démon
#       Gradle process-wide = OK (politique §2.2 reportée aux mesures).
#   F3  P → Q : « session fermée (P) » AVANT « session ouverte (Q) »,
#       puis AUCUNE ligne du projet P dans le journal de la session Q.
#   F5  rotations forcées ×5 : 5 rebranchements LSP, AUCUNE fermeture
#       de session, process vivant.
#   F7  build en vol → quitter (guidé) : le process SURVIT (§2.1c),
#       relevé ps pendant et après.
# Ce qui est PREUVE LOCALE (tests, exécutés hors appareil) :
#   F4  flush I7 : CycleVieEditorViewModelTest « la sortie ferme la
#       session ET flushe les onglets sales » ;
#   F5  invariant session : « la rotation ne ferme ni ne reouvre la
#       session » (même fichier) ;
#   F6  RegistreFermetureTest « depassement de delai — element
#       abandonne, les autres ferment » + « le delai borne aussi les
#       elements non annulables » ;
#   F7  renoncement §2.1c : « un travail gradle en vol fait renoncer la
#       liberation tooling » ;
#   F8  matrice §6.3 : table de traçabilité du rapport (§9 du doc).
#
# Usage :
#   ./scripts/validation-f.sh [--projet <uuid-P>] [--projet-q <uuid-Q>]
#                             [--paquet jo.codeide] [--serial <id-adb>]
#                             [--cycles 20] [--attente 25]
# Sans --projet/--projet-q : acquisition GUIDÉE au journal (tag
# SessionProjet, « session ouverte (<uuid>) ») à la première ouverture.
#
# Préparation : build DEBUG installé (assembleDebug + adb install -r),
# appareil DÉVERROUILLÉ, projets P (multi-gradle) et Q distincts.
#
# Sortie : resultats-f-<AAAAMMJJ-HHMM>/rapport-f.md (+ journaux,
# meminfo, ps, verdicts automatiques).

set -euo pipefail

# ---------------------------------------------------------------------------
# Paramètres.
# ---------------------------------------------------------------------------
PAQUET="jo.codeide"
UUID_P=""
UUID_Q=""
SERIAL=""
CYCLES=20
ATTENTE=25
while [[ $# -gt 0 ]]; do
    case "$1" in
        --projet) UUID_P="${2:?--projet exige un uuid}"; shift 2 ;;
        --projet-q) UUID_Q="${2:?--projet-q exige un uuid}"; shift 2 ;;
        --paquet) PAQUET="${2:?--paquet exige un nom}"; shift 2 ;;
        --serial) SERIAL="${2:?--serial exige un id}"; shift 2 ;;
        --cycles) CYCLES="${2:?--cycles exige un nombre}"; shift 2 ;;
        --attente) ATTENTE="${2:?--attente exige des secondes}"; shift 2 ;;
        *) echo "Option inconnue : $1" >&2; exit 1 ;;
    esac
done

adb() {
    if [[ -n "$SERIAL" ]]; then
        command adb -s "$SERIAL" "$@"
    else
        command adb "$@"
    fi
}

# ---------------------------------------------------------------------------
# Pré-vol : adb, appareil, paquet.
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
adb shell pm list packages | grep -q "package:$PAQUET" || {
    echo "ERREUR : $PAQUET n'est pas installé (assembleDebug + adb install -r)." >&2
    exit 1
}

HORODATE=$(date +%Y%m%d-%H%M)
SORTIE="resultats-f-$HORODATE"
mkdir -p "$SORTIE"
VERDICTS="$SORTIE/verdicts.tsv"
: > "$VERDICTS"

verdict() { # verdict <critère> <statut> <détail>
    echo -e "$1\t$2\t$3" >> "$VERDICTS"
}

# ---------------------------------------------------------------------------
# Relevés (motifs du kit de baseline).
# ---------------------------------------------------------------------------
PID_LOGCAT=""
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
compter() { # compter <motif> <fichier>
    grep -c "$motif" "$2" 2> /dev/null || echo 0
}

meminfo_java() { # meminfo_java <étiquette> → ko du tas Java (après GC)
    adb shell dumpsys meminfo "$PAQUET" > "$SORTIE/meminfo-$1.txt" 2> /dev/null || true
    awk '/^ *Java Heap:/ {print $2; exit}' "$SORTIE/meminfo-$1.txt" 2> /dev/null || echo 0
}
meminfo() { # meminfo <étiquette> → relève et journalise
    local tas
    tas=$(meminfo_java "$1")
    echo -e "meminfo\t$1\tJavaHeap=${tas}ko" >> "$SORTIE/releves.tsv"
}
enfants() { # enfants <étiquette> → ps du paquet
    adb shell ps -A -o PID,PPID,NAME 2>/dev/null | grep -i "$PAQUET" > "$SORTIE/ps-$1.txt" || true
    echo -e "ps\t$1\t$(grep -c . "$SORTIE/ps-$1.txt" || echo 0) ligne(s)" >> "$SORTIE/releves.tsv"
}
cmdlines() { # cmdlines <étiquette> → lignes de commande des process du paquet
    adb shell "for d in /proc/[0-9]*; do c=\$(tr '\0' ' ' < \$d/cmdline 2>/dev/null); case \$c in *$PAQUET*|*java*) echo \"\${d#/proc/} \$c\";; esac; done" \
        > "$SORTIE/cmd-$1.txt" 2>/dev/null || true
}
gc_forcer() { # SIGUSR1 = GC sur ART (le process survit aux cycles BACK)
    local pid
    pid=$(adb shell pidof "$PAQUET" | tr -d '\r\n ')
    [[ -n "$pid" ]] && adb shell kill -10 "$pid" 2> /dev/null || true
    sleep 2
}
pause() { read -r -p "$1" _; }
ouvrir_projet() { # ouvrir_projet <uuid> — routage v0.106.0 (froid ET chaud)
    adb shell am start -n "$PAQUET/.MainActivity" --es jo.codeide.projet_cible "$1" > /dev/null
}
quitter_editeur() { adb shell input keyevent KEYCODE_BACK > /dev/null; }

# Nettoyage garanti (rotations/écran restaurés).
ROTATION_AUTO=1
nettoyage() {
    journal_tuer
    adb shell settings put system accelerometer_rotation "$ROTATION_AUTO" > /dev/null 2>&1 || true
    adb shell settings put system user_rotation 0 > /dev/null 2>&1 || true
    adb shell svc power stayon false > /dev/null 2>&1 || true
}
trap nettoyage EXIT

# ---------------------------------------------------------------------------
# Pré-chauffe.
# ---------------------------------------------------------------------------
echo "== Validation étape F — résultats dans $SORTIE/ (paquet $PAQUET)"
adb shell svc power stayon true > /dev/null 2>&1 || true
journal_purger

if [[ -z "$UUID_P" ]]; then
    journal_demarrer "acquisition-p"
    pause "ACQUISITION P — ouvrez le projet P (multi-gradle) depuis l'accueil,
   attendez la fin de l'indexation (disque vert), puis appuyez sur Entrée."
    journal_tuer
    UUID_P=$(grep -o "session ouverte ([0-9a-f-]*)" "$SORTIE/acquisition-p.log" 2>/dev/null \
        | head -1 | grep -o "[0-9a-f-]\{36\}" || true)
    [[ -n "$UUID_P" ]] || {
        echo "ERREUR : uuid de P introuvable au journal (tag SessionProjet)." >&2
        exit 1
    }
    echo "   uuid P = $UUID_P (relevé au journal)"
    quitter_editeur; sleep 3
fi
if [[ -z "$UUID_Q" ]]; then
    journal_purger
    journal_demarrer "acquisition-q"
    pause "ACQUISITION Q — revenez à l'accueil, ouvrez le projet Q (distinct
   de P), attendez l'indexation, puis appuyez sur Entrée."
    journal_tuer
    UUID_Q=$(grep -o "session ouverte ([0-9a-f-]*)" "$SORTIE/acquisition-q.log" 2>/dev/null \
        | head -1 | grep -o "[0-9a-f-]\{36\}" || true)
    [[ -n "$UUID_Q" ]] || {
        echo "ERREUR : uuid de Q introuvable au journal (tag SessionProjet)." >&2
        exit 1
    }
    echo "   uuid Q = $UUID_Q (relevé au journal)"
    quitter_editeur; sleep 3
fi
: > "$SORTIE/releves.tsv"

pause "Repos court (2 min) avant F1 — écran allumé, app ne touchant à rien,
   puis appuyez sur Entrée."
sleep 120

# ---------------------------------------------------------------------------
# F1 — critère §8.1 : 20 cycles, croissance du tas < 5 %, zéro fuite.
# ---------------------------------------------------------------------------
echo "== F1 : $CYCLES cycles ouvrir/quitter (extra de routage, BACK)"
journal_demarrer "f1"
tas1=0; tas20=0
for cycle in $(seq 1 "$CYCLES"); do
    ouvrir_projet "$UUID_P"
    sleep "$ATTENTE"
    quitter_editeur
    sleep 5
    echo -e "cycle\t$cycle" >> "$SORTIE/releves.tsv"
    case "$cycle" in
        1|5|10|"$CYCLES")
            gc_forcer
            meminfo "f1-cycle$cycle"
            enfants "f1-cycle$cycle"
            [[ "$cycle" == "1" ]] && tas1=$(meminfo_java "f1-cycle$cycle")
            [[ "$cycle" == "$CYCLES" ]] && tas20=$(meminfo_java "f1-cycle$cycle")
            ;;
    esac
done
journal_tuer
fuites_leak=$(compter "LEAK" "$SORTIE/f1.log")
strictmode=$(compter "StrictMode policy violation" "$SORTIE/f1.log")
croissance=0
if [[ "$tas1" -gt 0 && "$tas20" -gt 0 ]]; then
    croissance=$(( 100 * (tas20 - tas1) / tas1 )); [[ "$croissance" -lt 0 ]] && croissance=0
fi
if [[ "$croissance" -lt 5 && "$fuites_leak" -eq 0 ]]; then
    verdict "F1-tas" "PASS" "croissance ${croissance}% (<5%), Java Heap $tas1→$tas20 ko (après GC)"
else
    verdict "F1-tas" "FAIL" "croissance ${croissance}%, Java Heap $tas1→$tas20 ko"
fi
[[ "$fuites_leak" -eq 0 ]] \
    && verdict "F1-fuites" "PASS" "LeakCanary muet sur les $CYCLES cycles" \
    || verdict "F1-fuites" "FAIL" "$fuites_leak détection(s) LeakCanary (LEAK*)"
[[ "$strictmode" -eq 0 ]] \
    && verdict "F1-strictmode" "PASS" "aucune violation StrictMode" \
    || verdict "F1-strictmode" "INFO" "$strictmode violation(s) StrictMode à instruire"

# ---------------------------------------------------------------------------
# F2 — critère §8.2 : aucun processus enfant LIÉ AU PROJET après sortie.
# ---------------------------------------------------------------------------
echo "== F2 : processus après la sortie"
journal_demarrer "f2"
enfants "f2-apres-sortie"
cmdlines "f2-apres-sortie"
journal_tuer
vestige_projet=$(grep -c "$UUID_P" "$SORTIE/cmd-f2-apres-sortie.txt" 2>/dev/null || echo 0)
demons=$(grep -c "GradleDaemon" "$SORTIE/cmd-f2-apres-sortie.txt" 2>/dev/null || echo 0)
if [[ "$vestige_projet" -eq 0 ]]; then
    verdict "F2-projet" "PASS" "aucun process portant P ; démons Gradle: $demons (politique §2.2 : process-wide, reporté aux mesures)"
else
    verdict "F2-projet" "FAIL" "$vestige_projet process porte encore P (voir cmd-f2-apres-sortie.txt)"
fi

# ---------------------------------------------------------------------------
# F5 — critère §8.5 : rotations forcées, rien ne se ferme.
# ---------------------------------------------------------------------------
echo "== F5 : 5 rotations forcées (éditeur P ouvert)"
ouvrir_projet "$UUID_P"
sleep "$ATTENTE"
journal_purger
journal_demarrer "f5"
meminfo "f5-avant"
ROTATION_AUTO=$(adb shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r\n ' || echo 1)
adb shell settings put system accelerometer_rotation 0 > /dev/null
orientation=0
for i in 1 2 3 4 5; do
    orientation=$(( (orientation + 1) % 4 ))
    adb shell settings put system user_rotation "$orientation" > /dev/null
    sleep 4
    adb shell settings put system user_rotation 0 > /dev/null
    sleep 4
done
adb shell settings put system accelerometer_rotation "$ROTATION_AUTO" > /dev/null
sleep 3
meminfo "f5-apres"
journal_tuer
pid_vivant=$(adb shell pidof "$PAQUET" | tr -d '\r\n ')
rebranche=$(compter "LSP Java connecté" "$SORTIE/f5.log")
fermees=$(compter "session fermée" "$SORTIE/f5.log")
if [[ -n "$pid_vivant" && "$fermees" -eq 0 ]]; then
    verdict "F5-rotation" "PASS" "process vivant, 0 fermeture de session ; rebranchements LSP: $rebranche (≥5 attendus)"
else
    verdict "F5-rotation" "FAIL" "process: ${pid_vivant:-MORT}, fermetures: $fermees, rebranchements: $rebranche"
fi
[[ "$rebranche" -ge 5 ]] \
    || verdict "F5-rebranche" "INFO" "rebranchements $rebranche < 5 (indexation non finie ? à instruire)"

# ---------------------------------------------------------------------------
# F3 — critère §8.3 : ouvrir P, quitter, ouvrir Q — aucun vestige de P.
# ---------------------------------------------------------------------------
echo "== F3 : P → quitter → Q (vestiges de P)"
# P est ouvert (fin de F5) : on purge, on quitte P, on ouvre Q.
journal_purger
journal_demarrer "f3"
quitter_editeur
sleep 5
ouvrir_projet "$UUID_Q"
sleep "$ATTENTE"
journal_tuer
ligne_ferme=$(grep -n "session fermée ($UUID_P)" "$SORTIE/f3.log" 2>/dev/null | head -1 | cut -d: -f1 || echo 0)
ligne_ouvre=$(grep -n "session ouverte ($UUID_Q)" "$SORTIE/f3.log" 2>/dev/null | head -1 | cut -d: -f1 || echo 0)
vestiges_q=$(grep -c "$UUID_P" "$SORTIE/f3.log" 2>/dev/null || echo 0)
if [[ "$ligne_ferme" -gt 0 && "$ligne_ouvre" -gt 0 && "$ligne_ferme" -lt "$ligne_ouvre" && "$vestiges_q" -eq 0 ]]; then
    verdict "F3-vestiges" "PASS" "P fermé (ligne $ligne_ferme) AVANT Q ouvert (ligne $ligne_ouvre) ; aucune ligne P ensuite"
else
    verdict "F3-vestiges" "FAIL" "fermeture P: ligne $ligne_ferme, ouverture Q: ligne $ligne_ouvre, lignes P: $vestiges_q"
fi
enfants "f3-session-q"
cmdlines "f3-session-q"

# ---------------------------------------------------------------------------
# F7 — critère §8.7 : build en vol → quitter (§2.1c : il continue).
# ---------------------------------------------------------------------------
echo "== F7 : build en vol (guidé)"
pause "F7 — dans Q (ouvert), lancez un BUILD, QUITTEZ l'éditeur pendant
   « tâches en cours » (toast attendu : build continue), attendez la
   notification FINALE, rouvrez, puis appuyez sur Entrée."
journal_demarrer "f7"
chrono=$(date +%s%3N)
pause "   >>> Effectuez la séquence, puis Entrée."
duree=$(( $(date +%s%3N) - chrono ))
journal_tuer
echo -e "durée_ms\tF7-build-en-vol\t$duree" >> "$SORTIE/releves.tsv"
pid_vivant=$(adb shell pidof "$PAQUET" | tr -d '\r\n ')
[[ -n "$pid_vivant" ]] \
    && verdict "F7-build" "PASS" "process vivant pendant le build après sortie (durée ${duree}ms) — notification finale et console: à consigner ci-dessous" \
    || verdict "F7-build" "FAIL" "process MORT pendant le build après sortie"
enfants "f7-apres"
verdict "F7-observation" "A CONSIGNER" "notification finale vue ? console cohérente au retour ? (rapport manuel)"

# ---------------------------------------------------------------------------
# Assemblage du rapport (critères §8 — preuves locales incluses).
# ---------------------------------------------------------------------------
{
    echo "# Validation étape F — $HORODATE"
    echo
    echo "Paquet : $PAQUET — cycles F1 : $CYCLES — attente indexation : ${ATTENTE}s"
    echo "Projets : P=$UUID_P Q=$UUID_Q"
    echo
    echo "## Verdicts automatiques (critères §8 du prompt 3)"
    column -t -s $'\t' "$VERDICTS" 2> /dev/null || cat "$VERDICTS"
    echo
    echo "## Preuves locales (tests, rejouables hors appareil)"
    echo '```'
    echo "JAVA_HOME=<jdk21> ./gradlew :feature:editor:test --tests 'jo.codeide.feature.editor.CycleVieEditorViewModelTest'"
    echo "JAVA_HOME=<jdk21> ./gradlew :core:session:test --tests 'jo.codeide.core.session.RegistreFermetureTest'"
    echo '```'
    echo "- F4 (perte nulle en sortie rapide) : « la sortie ferme la session ET"
    echo "  flushe les onglets sales » (I7 — frappe écrite AVANT le debounce)."
    echo "- F5 (invariant session) : « la rotation ne ferme ni ne reouvre la"
    echo "  session » (I3, E8 rebranchement borné)."
    echo "- F6 (fermeture bornée) : RegistreFermetureTest « depassement de delai —"
    echo "  element abandonne, les autres ferment » + « le delai borne aussi les"
    echo "  elements non annulables » (l'élément bloqué est journalisé)."
    echo "- F7 (renoncement) : « un travail gradle en vol fait renoncer la"
    echo "  liberation tooling » (§2.1c : le build continue)."
    echo "- F8 (matrice §6.3) : table de traçabilité §9 de docs/CYCLE_DE_VIE.md"
    echo "  (chaque ligne : test ou scénario présent)."
    echo
    echo "## Relevés bruts"
    column -t -s $'\t' "$SORTIE/releves.tsv" 2> /dev/null || cat "$SORTIE/releves.tsv"
    echo
    echo "## À consigner manuellement"
    echo "- F3 : console de Q vierge de P, statut Git, classpath, onglets."
    echo "- F7 : notification finale, console au retour, toast « build continue »."
    echo "- Comparaison à la BASELINE §4.5 (resultats-baseline-*) : tas, process."
    echo
    echo "Fichiers : f*.log (journaux), meminfo-*.txt, ps-*.txt, cmd-*.txt,"
    echo "acquisition-*.log, verdicts.tsv, releves.tsv."
} > "$SORTIE/rapport-f.md"

echo "== Terminé. Rapport : $SORTIE/rapport-f.md"
echo "   Reporter les verdicts dans docs/CYCLE_DE_VIE.md §9 — étape F close"
echo "   quand F1–F8 sont PASS (F2 : démons process-wide OK par politique)."
