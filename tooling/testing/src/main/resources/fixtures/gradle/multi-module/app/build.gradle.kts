plugins { java }

dependencies { implementation(project(":lib")) }

tasks.register("executer") {
    dependsOn(":lib:compiler")
    doLast { println("app exécuté avec lib") }
}
