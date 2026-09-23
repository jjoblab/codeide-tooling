plugins { java }

tasks.register("compiler") {
    doLast { println("lib compilé") }
}
