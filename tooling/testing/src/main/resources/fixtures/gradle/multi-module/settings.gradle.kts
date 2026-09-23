// Fixture G1 — projet multi-module (§7.2) : dépendance inter-projets,
// tâches qualifiées et chemins de tâches composés.
rootProject.name = "multi-module"
include(":app", ":lib")
