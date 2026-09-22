#!/usr/bin/env bash
# CodeIDE — installe les textes de licences de référence (étape 8).
# Apache-2.0 et GPL-3.0 : copies exactes des textes système (Debian,
# eux-mêmes répliques des textes officiels SPDX).
# MIT et BSD-3-Clause : texte officiel SPDX, avec {{year}}/{{author}}
# substitués par le moteur (exigence de l'étape 8).
set -euo pipefail

REPERTOIRE="$(dirname "$0")/../app/src/main/assets/licenses"
mkdir -p "$REPERTOIRE"

# --- MIT (texte officiel SPDX, copyright templatisé) -------------------
cat > "$REPERTOIRE/mit.txt" <<'EOF'
MIT License

Copyright (c) {{year}} {{author}}

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
EOF

# --- BSD-3-Clause (texte officiel SPDX, copyright templatisé) ----------
cat > "$REPERTOIRE/bsd-3-clause.txt" <<'EOF'
Copyright (c) {{year}} {{author}}. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
EOF

# --- Apache-2.0 (copie exacte du texte officiel) ----------------------
cp /usr/share/common-licenses/Apache-2.0 "$REPERTOIRE/apache-2.0.txt"

# --- GPL-3.0 (copie exacte du texte officiel) -------------------------
cp /usr/share/common-licenses/GPL-3 "$REPERTOIRE/gpl-3.0.txt"

# Normalisation : fins de ligne LF (le moteur normalise aussi).
for f in "$REPERTOIRE"/*.txt; do
    tr -d '\r' < "$f" > "$f.tmp" && mv "$f.tmp" "$f"
done

wc -c "$REPERTOIRE"/*.txt
echo "Licences installées dans $REPERTOIRE"
