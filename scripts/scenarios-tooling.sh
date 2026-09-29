#!/usr/bin/env bash
# CodeIDE — Scenario runner for Phase 0 (prompt de suivi, §1).
#
# Exécute des scénarios Gradle réels contre des fixtures et capture les
# sorties brutes pour observer les événements réellement émis par Gradle
# (et donc par le SyncHandler du tooling:server).
#
# Usage : ./scripts/scenarios-tooling.sh [--offline]
#
# Sortie : docs/tooling-scenarios/sorties/<scenario>.log pour chaque
# scénario, plus docs/tooling-scenarios/RAPPORT.md construit à la main
# après analyse.

set -eu

ROOT="/home/z/my-project/CodeIDE"
SCEN_DIR="$ROOT/docs/tooling-scenarios"
SORTIES="$SCEN_DIR/sorties"
FIXTURES="$SCEN_DIR/fixtures"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/z/.gradle}"

export JAVA_HOME="${JAVA_HOME:-/home/z/my-project/jdk-extracted/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/home/z/my-project/android-sdk}"
export GRADLE_USER_HOME
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$SORTIES" "$FIXTURES"
cd "$ROOT"

OFFLINE=""
if [[ "${1:-}" == "--offline" ]]; then
    OFFLINE="--offline"
fi

# Wrapper Gradle partagé (on prend la version déjà en cache pour gagner du temps).
GRADLE_BIN="$GRADLE_USER_HOME/wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1/bin/gradle"
GRADLE="$(ls $GRADLE_BIN 2>/dev/null | head -1)"
if [[ -z "$GRADLE" ]]; then
    echo "Distribution Gradle 9.7.1 introuvable dans $GRADLE_USER_HOME" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Helper : exécute un scénario et capture la sortie + trace de progression.
# ---------------------------------------------------------------------------

run_scenario() {
    local nom="$1"
    local fixture="$2"
    shift 2
    local args=("$@")
    local dest="$SORTIES/$nom.log"

    echo "==> Scénario : $nom (fixture=$fixture args=${args[*]:-})"

    # Copier la fixture vers un dossier temporaire (la fixture source
    # reste intacte, le .gradle n'y croît pas).
    local tmp
    tmp="$(mktemp -d "$FIXTURES/$nom.XXXX")"
    cp -r "$fixture/." "$tmp"

    # Lancer Gradle en mode plain (comme le serveur) avec --info pour
    # avoir tous les événements de progression (FILE_DOWNLOAD,
    # PROJECT_CONFIGURATION, etc.).
    (
        cd "$tmp"
        "$GRADLE" \
            --console=plain \
            --info \
            $OFFLINE \
            "${args[@]}" \
            tasks \
            > "$dest" 2>&1 || true
    )

    # Nettoyer le temporaire (sauf --keep).
    if [[ "${KEEP:-0}" != "1" ]]; then
        rm -rf "$tmp"
    fi

    # Taille + nombre de lignes pour vérifier
    local size
    size=$(wc -c < "$dest")
    local lines
    lines=$(wc -l < "$dest")
    echo "    -> $lines lignes, $size octets -> $dest"
}

# ---------------------------------------------------------------------------
# Fixtures : on réutilise celles de tooling:testing et on en génère quelques-unes.
# ---------------------------------------------------------------------------

# Fixture : projet Kotlin/JVM pur sans dépendances
mkdir -p "$FIXTURES/kotlin-jvm-pur/src/main/kotlin"
cat > "$FIXTURES/kotlin-jvm-pur/settings.gradle.kts" <<'EOF'
rootProject.name = "kotlin-jvm-pur"
EOF
cat > "$FIXTURES/kotlin-jvm-pur/build.gradle.kts" <<'EOF'
plugins {
    kotlin("jvm") version "2.0.21"
}
kotlin { jvmToolchain(21) }
EOF
mkdir -p "$FIXTURES/kotlin-jvm-pur/src/main/kotlin/demo"
cat > "$FIXTURES/kotlin-jvm-pur/src/main/kotlin/demo/Main.kt" <<'EOF'
package demo
fun main() { println("Hello") }
EOF

# Fixture : projet Kotlin/JVM avec dépendance (okio)
mkdir -p "$FIXTURES/kotlin-jvm-avec-deps/src/main/kotlin"
cat > "$FIXTURES/kotlin-jvm-avec-deps/settings.gradle.kts" <<'EOF'
rootProject.name = "kotlin-jvm-avec-deps"
EOF
cat > "$FIXTURES/kotlin-jvm-avec-deps/build.gradle.kts" <<'EOF'
plugins {
    kotlin("jvm") version "2.0.21"
}
repositories { mavenCentral() }
dependencies {
    implementation("com.squareup.okio:okio:3.9.0")
}
kotlin { jvmToolchain(21) }
EOF
mkdir -p "$FIXTURES/kotlin-jvm-avec-deps/src/main/kotlin/demo"
cat > "$FIXTURES/kotlin-jvm-avec-deps/src/main/kotlin/demo/Main.kt" <<'EOF'
package demo
import okio.Buffer
fun main() { println(Buffer().writeUtf8("Hello").readUtf8()) }
EOF

# Fixture : projet multi-modules Kotlin/JVM (app + lib)
mkdir -p "$FIXTURES/kotlin-multi-modules/app/src/main/kotlin/demo" "$FIXTURES/kotlin-multi-modules/lib/src/main/kotlin/lib"
cat > "$FIXTURES/kotlin-multi-modules/settings.gradle.kts" <<'EOF'
rootProject.name = "kotlin-multi-modules"
include(":app", ":lib")
EOF
cat > "$FIXTURES/kotlin-multi-modules/build.gradle.kts" <<'EOF'
plugins {
    kotlin("jvm") version "2.0.21" apply false
}
EOF
cat > "$FIXTURES/kotlin-multi-modules/app/build.gradle.kts" <<'EOF'
plugins { kotlin("jvm") }
dependencies { implementation(project(":lib")) }
kotlin { jvmToolchain(21) }
EOF
cat > "$FIXTURES/kotlin-multi-modules/lib/build.gradle.kts" <<'EOF'
plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
EOF
cat > "$FIXTURES/kotlin-multi-modules/app/src/main/kotlin/demo/Main.kt" <<'EOF'
package demo
fun main() { println(lib.Saluer().saluer()) }
EOF
cat > "$FIXTURES/kotlin-multi-modules/lib/src/main/kotlin/lib/Saluer.kt" <<'EOF'
package lib
class Saluer { fun saluer() = "Hello" }
EOF

# Fixture : Groovy DSL
mkdir -p "$FIXTURES/groovy-dsl/src/main/java/demo"
cat > "$FIXTURES/groovy-dsl/settings.gradle" <<'EOF'
rootProject.name = "groovy-dsl"
EOF
cat > "$FIXTURES/groovy-dsl/build.gradle" <<'EOF'
apply plugin: 'java'
repositories { mavenCentral() }
dependencies { implementation 'com.squareup.okio:okio:3.9.0' }
EOF
cat > "$FIXTURES/groovy-dsl/src/main/java/demo/Main.java" <<'EOF'
package demo;
public class Main { public static void main(String[] a) { System.out.println("Hello"); } }
EOF

# Fixture : projet avec buildSrc
mkdir -p "$FIXTURES/avec-buildsrc/buildSrc/src/main/kotlin" "$FIXTURES/avec-buildsrc/src/main/kotlin/demo"
cat > "$FIXTURES/avec-buildsrc/settings.gradle.kts" <<'EOF'
rootProject.name = "avec-buildsrc"
EOF
cat > "$FIXTURES/avec-buildsrc/buildSrc/build.gradle.kts" <<'EOF'
plugins { `kotlin-dsl` }
repositories { mavenCentral() }
EOF
cat > "$FIXTURES/avec-buildsrc/buildSrc/src/main/kotlin/monplugin.gradle.kts" <<'EOF'
plugins { `kotlin-dsl` }
EOF
cat > "$FIXTURES/avec-buildsrc/build.gradle.kts" <<'EOF'
plugins {
    kotlin("jvm") version "2.0.21"
}
EOF
cat > "$FIXTURES/avec-buildsrc/src/main/kotlin/demo/Main.kt" <<'EOF'
package demo
fun main() { println("Hello") }
EOF

# Fixture : échec de configuration (plugin inexistant)
mkdir -p "$FIXTURES/erreur-config/src/main/kotlin"
cat > "$FIXTURES/erreur-config/settings.gradle.kts" <<'EOF'
rootProject.name = "erreur-config"
EOF
cat > "$FIXTURES/erreur-config/build.gradle.kts" <<'EOF'
plugins {
    id "plugin.inexistant.et.bidon" version "1.0.0"
}
EOF

# ---------------------------------------------------------------------------
# Scénarios exécutables SANS Internet (cache déjà chaud pour AndroidX).
# Pour les scénarios de distribution Gradle / toolchain JDK, on simule
# en changeant la version du wrapper — voir scénarios "wrapper-modifie".
# ---------------------------------------------------------------------------

# Scénario 1 : Kotlin/JVM pur, daemon froid (GRADLE_USER_HOME chaud)
run_scenario "01-kotlin-jvm-pur-daemon-froid" "$FIXTURES/kotlin-jvm-pur"

# Scénario 2 : Kotlin/JVM pur, daemon vivant (deuxième run)
run_scenario "02-kotlin-jvm-pur-daemon-vivant" "$FIXTURES/kotlin-jvm-pur"

# Scénario 3 : Kotlin/JVM avec dépendances (premier run — téléchargements)
run_scenario "03-kotlin-jvm-avec-deps-premier-run" "$FIXTURES/kotlin-jvm-avec-deps"

# Scénario 4 : Kotlin/JVM avec dépendances (deuxième run — tout chaud)
run_scenario "04-kotlin-jvm-avec-deps-chaud" "$FIXTURES/kotlin-jvm-avec-deps"

# Scénario 5 : Multi-module Kotlin/JVM (configuration de 3 modules)
run_scenario "05-multi-modules" "$FIXTURES/kotlin-multi-modules"

# Scénario 6 : Groovy DSL
run_scenario "06-groovy-dsl" "$FIXTURES/groovy-dsl"

# Scénario 7 : Projet avec buildSrc (compilation séparée)
run_scenario "07-avec-buildsrc" "$FIXTURES/avec-buildsrc"

# Scénario 8 : Échec de configuration
run_scenario "08-erreur-config" "$FIXTURES/erreur-config"

# Scénario 9 : Mode hors ligne avec cache complet (devrait réussir)
run_scenario "09-offline-cache-complet" "$FIXTURES/kotlin-jvm-avec-deps" --offline

# Scénario 10 : Mode hors ligne avec cache incomplet (échec attendu)
mkdir -p "$FIXTURES/avec-deps-non-cachees/src/main/kotlin"
cat > "$FIXTURES/avec-deps-non-cachees/settings.gradle.kts" <<'EOF'
rootProject.name = "deps-non-cachees"
EOF
cat > "$FIXTURES/avec-deps-non-cachees/build.gradle.kts" <<'EOF'
plugins { kotlin("jvm") version "2.0.21" }
repositories { mavenCentral() }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1") }
kotlin { jvmToolchain(21) }
EOF
mkdir -p "$FIXTURES/avec-deps-non-cachees/src/main/kotlin/demo"
cat > "$FIXTURES/avec-deps-non-cachees/src/main/kotlin/demo/Main.kt" <<'EOF'
package demo
import kotlinx.datetime.Clock
fun main() { println(Clock.System.now()) }
EOF
# Scénario 10 en ligne — pour observer les téléchargements
run_scenario "10-deps-kotlinx-datetime" "$FIXTURES/avec-deps-non-cachees"

echo
echo "==> Scénarios terminés. Sorties dans $SORTIES/"
ls -la "$SORTIES"
