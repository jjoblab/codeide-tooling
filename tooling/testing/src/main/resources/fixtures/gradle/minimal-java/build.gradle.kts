// Fixture G1 — une tâche maison vérifiable + compilation Java minimale.
plugins { java }

tasks.register("saluer") {
    doLast { println("Bonjour depuis minimal-java") }
}
