package com.exemple.essai;

/** Activité factice — la fixture éprouve le classpath, pas le build. */
public final class MainActivity {
    public static void main(String[] args) {
        System.out.println(com.exemple.biblio.Outil.saluer("CodeIDE"));
    }
}
