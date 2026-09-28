#!/usr/bin/env bash
# CodeIDE — vérifie qu'un APK debug porte bien la signature du keystore
# public versionné (ADR 0067).
# Usage : scripts/verify-signature.sh <chemin/vers/apk>
#   1. empreinte SHA-256 du certificat de config/signature/debug.keystore ;
#   2. empreinte SHA-256 du signataire de l'APK (apksigner --print-certs) ;
#   3. échoue si elles diffèrent — la signature d'un APK livré doit être
#      l'identité versionnée, sinon les mises à jour « conflit de package »
#      reviennent (ADR 0063, retour v0.35.2 puis v0.37.1).

set -euo pipefail

# ---------------------------------------------------------------------------
# Paramètres et prérequis.
# ---------------------------------------------------------------------------
apk="${1:-}"
[ -n "$apk" ] && [ -f "$apk" ] || { echo "Usage : $0 <chemin/vers/apk>" >&2; exit 1; }

keystore="config/signature/debug.keystore"
[ -f "$keystore" ] || { echo "ERREUR : $keystore introuvable (ADR 0067)" >&2; exit 1; }

keytool="${JAVA_HOME:-}/bin/keytool"
if [ ! -x "$keytool" ]; then
    keytool=$(command -v keytool) || { echo "ERREUR : keytool introuvable" >&2; exit 1; }
fi

# apksigner : build-tools le plus récent du SDK — ANDROID_HOME, sinon le
# sdk.dir de local.properties (le runner GitHub expose ANDROID_HOME, le
# bac à sable n'a que local.properties).
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | head -1)
fi
if [ -n "$sdk" ] && [ -d "$sdk" ]; then
    apksigner=$(ls -1 "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
else
    apksigner=""
fi
[ -n "$apksigner" ] && [ -x "$apksigner" ] || {
    echo "ERREUR : apksigner introuvable dans les build-tools du SDK ($sdk)" >&2
    exit 1
}

# ---------------------------------------------------------------------------
# Empreintes (SHA-256 du certificat, hexadécimal sans séparateur).
# ---------------------------------------------------------------------------
# keytool imprime « SHA256: XX:XX:… » (séparateurs deux-points), apksigner
# imprime « Signer #1 certificate SHA-256 digest: xxxx… » (hexa compact).
# Les deux motifs capturent exactement 32 octets — SHA-1 (20 octets) et les
# condensés partiels ne peuvent pas matcher.
empreinte_keystore=$($keytool -list -v -keystore "$keystore" -storepass android 2>/dev/null |
    grep -m1 -oE '[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){31}' | tr -d ':' | tr 'A-F' 'a-f')
[ -n "$empreinte_keystore" ] || {
    echo "ERREUR : empreinte du keystore illisible" >&2
    exit 1
}

empreinte_apk=$("$apksigner" verify --print-certs "$apk" |
    grep -m1 -oiE '[0-9a-f]{2}(:[0-9a-f]{2}){31}|[0-9a-f]{64}' | tr -d ':' | tr 'A-F' 'a-f')
[ -n "$empreinte_apk" ] || {
    echo "ERREUR : empreinte du signataire de l'APK illisible" >&2
    exit 1
}

# ---------------------------------------------------------------------------
# Comparaison.
# ---------------------------------------------------------------------------
if [ "$empreinte_keystore" != "$empreinte_apk" ]; then
    echo "ERREUR : l'APK n'est pas signé par le keystore versionné (ADR 0067)" >&2
    echo "  keystore : $empreinte_keystore" >&2
    echo "  apk      : $empreinte_apk" >&2
    exit 1
fi

echo "[verify-signature] SUCCÈS : l'APK est signé par le keystore versionné"
echo "[verify-signature] SHA-256 du certificat : $empreinte_apk"
