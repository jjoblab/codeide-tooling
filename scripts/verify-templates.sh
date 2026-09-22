#!/usr/bin/env bash
# CodeIDE — validation réelle des modèles embarqués (étape 9, section 11).
#
# Génère sur disque un jeu de projets couvrant les combinaisons d'options
# (tous les triplets langage × type × build, chaque JDK au moins une fois,
# avec/sans tests, avec/sans wrapper, les deux langues, plusieurs licences,
# des noms et descriptions hostiles) via le harnais :tools:generateur,
# puis COMPILE, TESTE et EXÉCUTE chaque projet avec les vrais outils
# (Gradle Wrapper, Maven, javac, projet Gradle jetable pour `none`+Kotlin).
#
# Usage : scripts/verify-templates.sh
# Surcharge d'environnement (chemins des outils) :
#   CODEIDE_JDK17, CODEIDE_JDK21, CODEIDE_MAVEN, CODEIDE_TRAVAIL
# Le tableau « combinaison → résultat » est affiché en fin d'exécution ;
# le code de sortie est non nul au moindre échec.

set -euo pipefail

RACINE=$(cd "$(dirname "$0")/.." && pwd)
TRAVAIL="${CODEIDE_TRAVAIL:-$RACINE/build/verify-templates}"
JDK17="${CODEIDE_JDK17:-$(compgen -G '/home/z/tools/jdk-17*' | head -1 || true)}"
JDK21="${CODEIDE_JDK21:-/home/z/tools/jdk-21}"
MVN_BIN="${CODEIDE_MAVEN:-$(compgen -G '/home/z/tools/apache-maven-*/bin/mvn' | head -1 || command -v mvn || true)}"
# Distribution Gradle locale (projets générés SANS wrapper : build avec le
# Gradle système, comme un utilisateur qui n'embarque pas le wrapper).
GRADLE_DIS="$(compgen -G "$HOME/.gradle/wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1/bin/gradle" | head -1 || true)"

echec_global=0
declare -a lignes_tableau=()

note() { printf '\033[1;34m[verify-templates]\033[0m %s\n' "$*"; }
echec() { printf '\033[1;31m[verify-templates]\033[0m %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# 0. Outils présents ?
# ---------------------------------------------------------------------------
[ -d "$JDK17" ] || { echec "JDK 17 introuvable (CODEIDE_JDK17) : $JDK17"; exit 1; }
[ -d "$JDK21" ] || { echec "JDK 21 introuvable (CODEIDE_JDK21) : $JDK21"; exit 1; }
[ -n "$MVN_BIN" ] && [ -x "$MVN_BIN" ] || { echec "Maven introuvable (CODEIDE_MAVEN)"; exit 1; }
note "JDK 17 : $JDK17"
note "JDK 21 : $JDK21"
note "Maven  : $MVN_BIN"
if [ -n "$GRADLE_DIS" ]; then
    note "Gradle (sans wrapper) : $GRADLE_DIS"
else
    note "Gradle système absent : les projets sans wrapper seront construits via un wrapper voisin"
fi

mkdir -p "$TRAVAIL"

# ---------------------------------------------------------------------------
# 1. Matrice des combinaisons (source unique : le script Python ci-dessous
#    produit le JSON du harnais ET le tableau pilotant la boucle de build).
# ---------------------------------------------------------------------------
python3 - "$TRAVAIL" <<'PY'
import json
import sys

travail = sys.argv[1]

# id, template, type, build, jdk, tests, wrapper, langue, licence, nom, description
AUTEUR = "CodeIDE"
combos = [
    # — Tous les triplets langage × type × build, chaque JDK, options variées.
    ("kt-app-gradle-21", "kotlin-jvm", "application", "gradle-kts", "21", True, True, "fr", "mit",
     "Projet $ & % \U0001F680 très-long", ""),
    ("kt-lib-gradle-21", "kotlin-jvm", "library", "gradle-kts", "21", True, True, "en", "apache-2.0",
     "Bibliotheque Kotlin", "A Kotlin library"),
    ("kt-app-maven-21", "kotlin-jvm", "application", "maven", "21", True, False, "fr", "gpl-3.0",
     "Application Maven Kotlin", "Un \"piege\" </project> \\ avec $dollars$"),
    ("kt-lib-maven-17", "kotlin-jvm", "library", "maven", "17", True, False, "en", "mit",
     "Bibliotheque Maven", ""),
    ("kt-app-none", "kotlin-jvm", "application", "none", "", False, False, "fr", "none",
     "Kotlin Sans Build", ""),
    ("kt-lib-none", "kotlin-jvm", "library", "none", "", False, False, "en", "bsd-3-clause",
     "Bibliotheque Sans Build", ""),
    ("kt-app-gradle-17", "kotlin-jvm", "application", "gradle-kts", "17", True, False, "en", "none",
     "Kotlin Sans Wrapper", ""),
    ("kt-lib-gradle-17", "kotlin-jvm", "library", "gradle-kts", "17", False, True, "fr", "apache-2.0",
     "Bibliotheque Sans Tests", ""),
    ("jv-app-gradle-21", "java", "application", "gradle-kts", "21", True, True, "en", "mit",
     "Java Application", ""),
    ("jv-lib-gradle-17", "java", "library", "gradle-kts", "17", True, True, "fr", "gpl-3.0",
     "Bibliotheque Java", "Une bibliotheque Java"),
    ("jv-app-maven-17", "java", "application", "maven", "17", True, False, "en", "none",
     "Java Maven App", ""),
    ("jv-lib-maven-21", "java", "library", "maven", "21", True, False, "fr", "bsd-3-clause",
     "Bibliotheque Java Maven", ""),
    ("jv-app-none", "java", "application", "none", "", False, False, "en", "none",
     "Java Sans Build", ""),
    ("jv-lib-none", "java", "library", "none", "", False, False, "fr", "apache-2.0",
     "Bibliotheque Java Sans Build", ""),
    ("jv-app-gradle-21b", "java", "application", "gradle-kts", "21", False, False, "fr", "none",
     "Java Sans Tests Ni Wrapper", ""),
    ("jv-lib-gradle-21b", "java", "library", "gradle-kts", "21", True, False, "en", "mit",
     "Bibliotheque Java Sans Wrapper", ""),
    ("kt-app-gradle-21b", "kotlin-jvm", "application", "gradle-kts", "21", True, True, "en", "none",
     "Kotlin Hostile", "Description \\ \"hostile\" $ </script> & 'quote'"),
    ("jv-app-maven-21b", "java", "application", "maven", "21", True, False, "fr", "mit",
     "Console & CLI % 2026 \U0001F600", "Autre description \"hostile\" \\ $x$"),
]

entrees = []
tsv = []
for (cid, template, type_, build, jdk, tests, wrapper, langue, licence, nom, desc) in combos:
    parametres = {"projectType": type_, "buildSystem": build}
    manuels = ["projectType", "buildSystem"]
    if build != "none":
        parametres["jdkVersion"] = jdk
        parametres["includeTests"] = "true" if tests else "false"
        manuels += ["jdkVersion", "includeTests"]
        parametres["packageName"] = f"io.codeide.verification.{cid.replace('-', '')}"
        parametres["groupId"] = "io.codeide.verification"
        parametres["artifactId"] = cid
        parametres["version"] = "0.1.0"
        manuels += ["packageName", "groupId", "artifactId", "version"]
    if build == "gradle-kts":
        parametres["includeWrapper"] = "true" if wrapper else "false"
        manuels += ["includeWrapper"]
    entrees.append({
        "id": cid,
        "templateId": template,
        "nom": nom,
        "description": desc,
        "parametres": parametres,
        "modifiesManuellement": sorted(manuels),
        "options": {
            "license": licence,
            "contentLanguage": langue,
        },
    })
    tsv.append("\t".join([
        cid, template, type_, build, jdk or "-",
        "tests" if tests else "notests",
        "wrapper" if wrapper else "nowrapper",
        langue, licence,
        "app" if type_ == "application" else "lib",
    ]))

with open(f"{travail}/combos.json", "w", encoding="utf-8") as f:
    json.dump(entrees, f, ensure_ascii=False, indent=2)
with open(f"{travail}/matrix.tsv", "w", encoding="utf-8") as f:
    f.write("\n".join(tsv) + "\n")
print(f"{len(entrees)} combinaisons écrites")
PY

# ---------------------------------------------------------------------------
# 2. Génération sur disque (harnais JVM — ADR 0019).
# ---------------------------------------------------------------------------
note "Génération des projets dans $TRAVAIL/modeles …"
(cd "$RACINE" && ./gradlew -q :tools:generateur:run \
    "--args=--assets $RACINE/app/src/main/assets --sortie $TRAVAIL/modeles --combos $TRAVAIL/combos.json --annee 2026 --auteur CodeIDE --generateur 0.10.0")

# ---------------------------------------------------------------------------
# 3. Vérifications structurelles + builds réels, combinaison par combinaison.
# ---------------------------------------------------------------------------
SOMME_WRAPPER=$(sha256sum "$RACINE/gradle/wrapper/gradle-wrapper.jar" | cut -d' ' -f1)
PREMIERE_LIGNE_FR="Bonjour, Ada!"
PREMIERE_LIGNE_EN="Hello, Ada!"

verifier_structure() {
    local projet="$1"
    local restants
    restants=$(grep -rIl --exclude=".verifie" '{{' "$projet" || true)
    if [ -n "$restants" ]; then
        echec "marqueur {{ résiduel : $restants"
        return 1
    fi
    local boms
    boms=$(grep -rIl --exclude=".verifie" $'\xef\xbb\xbf' "$projet" || true)
    if [ -n "$boms" ]; then
        echec "BOM UTF-8 interdit : $boms"
        return 1
    fi
    if [ -f "$projet/gradlew" ] && grep -q $'\r' "$projet/gradlew"; then
        echec "gradlew doit être en LF"
        return 1
    fi
    if [ -f "$projet/gradlew.bat" ]; then
        if ! grep -q $'\r' "$projet/gradlew.bat"; then
            echec "gradlew.bat doit être en CRLF"
            return 1
        fi
        local somme
        somme=$(sha256sum "$projet/gradle/wrapper/gradle-wrapper.jar" | cut -d' ' -f1)
        if [ "$somme" != "$SOMME_WRAPPER" ]; then
            echec "gradle-wrapper.jar altéré (checksum)"
            return 1
        fi
    fi
    return 0
}

lancer_sortie_attendue() { # $1 projet $2 première ligne attendue
    local projet="$1" attendu="$2" sortie
    sortie=$(cd "$projet" && JAVA_HOME="$JDK21" ./gradlew -q run 2>/dev/null | head -1 || true)
    if [ "$sortie" != "$attendu" ]; then
        echec "sortie inattendue : « $sortie » (attendu « $attendu »)"
        return 1
    fi
    return 0
}

combinaison_ko() {
    lignes_tableau+=("$(printf '%-22s | %s' "$1" "KO — $2")")
    echec_global=1
}
combinaison_ok() {
    lignes_tableau+=("$(printf '%-22s | %s' "$1" "OK")")
}

# Empreinte des assets : un marqueur ne vaut que pour les assets qui l'ont produit.
EMPREINTE_ASSETS=$( (find "$RACINE/app/src/main/assets/templates" -type f | sort | xargs sha256sum; \
    sha256sum "$RACINE/gradle/wrapper/gradle-wrapper.jar") 2>/dev/null | sha256sum | cut -d' ' -f1)

note "Builds réels (18 combinaisons) — ceci prend plusieurs minutes…"
while IFS=$'\t' read -r cid template type_ build jdk tests wrapper langue licence role; do
    projet="$TRAVAIL/modeles/$cid"
    erreur=""
    marqueur="$projet/.verifie"
    if [ -f "$marqueur" ] && [ "$(cat "$marqueur")" = "$EMPREINTE_ASSETS" ]; then
        combinaison_ok "$cid"
        note "OK   $cid (déjà vérifié)"
        continue
    fi
    if ! verifier_structure "$projet"; then
        erreur="structure"
    fi

    jdk_home="$JDK21"
    [ "$jdk" = "17" ] && jdk_home="$JDK17"

    if [ -z "$erreur" ]; then
        case "$build" in
        gradle-kts)
            gradle_cmd=()
            if [ -f "$projet/gradlew" ]; then
                chmod +x "$projet/gradlew"
                gradle_cmd=("$projet/gradlew")
            elif [ -n "$GRADLE_DIS" ]; then
                gradle_cmd=("$GRADLE_DIS")
            else
                erreur="ni wrapper ni Gradle système"
            fi
            if [ -z "$erreur" ]; then
                taches="build"
                [ "$role" = "lib" ] && taches="build publishToMavenLocal"
                if ! (cd "$projet" && JAVA_HOME="$jdk_home" "${gradle_cmd[0]}" --no-daemon --console=plain -q $taches >/dev/null 2>"$TRAVAIL/$cid.log"); then
                    erreur="gradle ($taches)"
                elif [ "$tests" = "tests" ] && [ ! -f "$projet/build/reports/tests/test/index.html" ]; then
                    erreur="rapport de tests Gradle absent"
                elif [ "$role" = "app" ]; then
                    if ! (cd "$projet" && JAVA_HOME="$jdk_home" "${gradle_cmd[0]}" --no-daemon -q run >"$TRAVAIL/$cid.sortie" 2>>"$TRAVAIL/$cid.log"); then
                        erreur="gradle run"
                    elif [ "$langue" = "fr" ] && ! grep -q "$PREMIERE_LIGNE_FR" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (fr)"
                    elif [ "$langue" = "en" ] && ! grep -q "$PREMIERE_LIGNE_EN" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (en)"
                    fi
                fi
            fi
            ;;
        maven)
            taches="verify"
            [ "$role" = "lib" ] && taches="verify install"
            if ! (cd "$projet" && JAVA_HOME="$jdk_home" "$MVN_BIN" -q $taches >"$TRAVAIL/$cid.log" 2>&1); then
                erreur="maven ($taches)"
            elif [ "$role" = "app" ]; then
                if ! (cd "$projet" && JAVA_HOME="$jdk_home" "$MVN_BIN" -q compile exec:java >"$TRAVAIL/$cid.sortie" 2>>"$TRAVAIL/$cid.log"); then
                    erreur="maven exec:java"
                elif [ "$langue" = "fr" ] && ! grep -q "$PREMIERE_LIGNE_FR" "$TRAVAIL/$cid.sortie"; then
                    erreur="sortie application inattendue (fr)"
                elif [ "$langue" = "en" ] && ! grep -q "$PREMIERE_LIGNE_EN" "$TRAVAIL/$cid.sortie"; then
                    erreur="sortie application inattendue (en)"
                fi
            fi
            if [ -z "$erreur" ] && [ "$tests" = "tests" ]; then
                rapports=$(ls "$projet"/target/surefire-reports/*.txt 2>/dev/null | head -1 || true)
                if [ -z "$rapports" ] || ! grep -q "Tests run: 3" "$rapports"; then
                    erreur="tests Maven non exécutés"
                fi
            fi
            ;;
        none)
            if [ "$template" = "java" ]; then
                if ! (cd "$projet" && mkdir -p out && "$jdk_home/bin/javac" -encoding UTF-8 -Xlint:all -Werror \
                    -d out $(find src/main/java -name '*.java')); then
                    erreur="javac"
                elif [ "$role" = "app" ]; then
                    paquet=$(grep -rh '^package ' "$projet/src/main/java" | head -1 | sed 's/package //;s/;//')
                    if ! (cd "$projet" && "$jdk_home/bin/java" -cp out "$paquet.Main" >"$TRAVAIL/$cid.sortie" 2>&1); then
                        erreur="java"
                    elif [ "$langue" = "fr" ] && ! grep -q "$PREMIERE_LIGNE_FR" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (fr)"
                    elif [ "$langue" = "en" ] && ! grep -q "$PREMIERE_LIGNE_EN" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (en)"
                    fi
                fi
            else
                # Kotlin sans build : compilation par copie dans un projet Gradle jetable.
                paquet=$(grep -rh '^package ' "$projet/src/main/kotlin" | head -1 | sed 's/package //;s/;//')
                jetable=$(mktemp -d)
                mkdir -p "$jetable/src/main"
                cp -r "$projet/src/main/kotlin" "$jetable/src/main/"
                { printf 'plugins { kotlin("jvm") version "2.2.21"\n'
                  if [ "$role" = "app" ]; then printf 'application\n}\napplication { mainClass.set("%s.MainKt") }\n' "$paquet"
                  else printf '}\n'; fi
                  printf 'repositories { mavenCentral() }\n'; } > "$jetable/build.gradle.kts"
                printf 'rootProject.name = "jetable-%s"\n' "$cid" > "$jetable/settings.gradle.kts"
                tache=build
                [ "$role" = "app" ] && tache=run
                if [ -n "$GRADLE_DIS" ]; then
                    if ! (cd "$jetable" && JAVA_HOME="$jdk_home" "$GRADLE_DIS" --no-daemon -q $tache >"$TRAVAIL/$cid.sortie" 2>"$TRAVAIL/$cid.log"); then
                        erreur="projet jetable ($tache)"
                    fi
                else
                    erreur="Gradle système absent pour le projet jetable"
                fi
                if [ -z "$erreur" ] && [ "$role" = "app" ]; then
                    if [ "$langue" = "fr" ] && ! grep -q "$PREMIERE_LIGNE_FR" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (fr)"
                    elif [ "$langue" = "en" ] && ! grep -q "$PREMIERE_LIGNE_EN" "$TRAVAIL/$cid.sortie"; then
                        erreur="sortie application inattendue (en)"
                    fi
                fi
                rm -rf "$jetable"
            fi
            ;;
        esac
    fi

    if [ -z "$erreur" ]; then
        combinaison_ok "$cid"
        printf '%s' "$EMPREINTE_ASSETS" > "$marqueur"
        note "OK   $cid"
    else
        combinaison_ko "$cid" "$erreur"
        echec "KO   $cid : $erreur (journal : $TRAVAIL/$cid.log)"
    fi
done < "$TRAVAIL/matrix.tsv"

# ---------------------------------------------------------------------------
# 4. Tableau final.
# ---------------------------------------------------------------------------
echo
echo "═══ Validation réelle des modèles — tableau combinaison → résultat ═══"
printf '%-22s | %s\n' "combinaison" "résultat"
printf '%s\n' "-----------------------+-------------------------------------------"
for ligne in "${lignes_tableau[@]}"; do
    echo "$ligne"
done
echo "--------------------------------------------------------------------------"

if [ "$echec_global" -ne 0 ]; then
    echec "AU MOINS UNE COMBINAISON A ÉCHOUÉ (journaux dans $TRAVAIL)"
    exit 1
fi
note "Toutes les combinaisons sont vertes."
