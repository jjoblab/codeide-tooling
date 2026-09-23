// Durée pilotée par -PdureeMs=... (défaut : 10 s) — jamais infinie :
// un test qui échoue doit se terminer seul.
tasks.register("endormir") {
    doLast {
        val dureeMs = (project.findProperty("dureeMs") ?: "10000").toString().toLong()
        Thread.sleep(dureeMs)
        println("réveil après ${dureeMs} ms")
    }
}
