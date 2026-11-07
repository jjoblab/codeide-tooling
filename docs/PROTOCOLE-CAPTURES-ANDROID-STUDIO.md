# PROTOCOLE-CAPTURES-ANDROID-STUDIO — build témoin chronométré dans Android Studio (Phase 3, §5.3)

> **Statut : protocole à exécuter par le propriétaire.** Objectif : obtenir
> des **cibles chiffrées réelles** d'Android Studio sur la machine du
> propriétaire, pour caler les cibles proposées de
> `REFERENCE-ANDROID-STUDIO.md`. Durée totale estimée : **30–40 minutes**,
> un seul enregistrement d'écran requis. Aucun envoi de données : tout se
> relève à l'écran.

## 1. Préparation (5 minutes)

1. **Machine de référence** : celle du quotidien (la plus proche possible
   de la machine utilisée pour les mesures CodeIDE — le RAPPORT d'écart
   importe plus que la machine absolue).
2. **Projet témoin** : le MÊME projet que celui des builds du journal de
   terrain (les trois `assembleDebug` en échec) — sinon un projet Android
   multi-modules moyen. **Ne pas nettoyer** les caches : on mesure l'état
   quotidien.
3. **Android Studio à jour**, fenêtre Build ouverte (View > Tool Windows >
   Build), onglets Sync et Build Output visibles.
4. Lancer un **enregistrement d'écran** plein cadre (horloge système
   visible à l'image si possible — sinon l'heure de début notée).

## 2. Séquence chronométrée (15 minutes)

Répéter chaque scénario **3 fois** de suite (froid puis chauds) :

| # | Scénario | Points à relever |
|---|---|---|
| C1 | Ouvrir le projet (sync d'ouverture) | Instant du clic ; PREMIÈRE ligne visible dans l'onglet Sync ; instant du « Sync finished » (ou équivalent) ; la sync est-elle annulée par Stop ? (essayer une fois) |
| C2 | `Build > Make Project` (réussite) | Instant du raccourci ; première ligne de Build Output ; première tâche `> Task` affichée ; conclusion affichée ; les lignes semblent-elles arriver EN CONTINU ou PAR BLOCS ? |
| C3 | Build en ÉCHEC volontaire (le même scénario que le journal CodeIDE — casser un fichier source) | Instant de l'erreur compilée visible dans la fenêtre vs instant du « BUILD FAILED » ; délai entre le verdict affiché et la DERNIÈRE ligne de diagnostic |
| C4 | Pendant C2/C3 : **faire défiler** la fenêtre Build en cours de build | Fluidité du défilement (subjectif : fluide / saccadé / gel) |
| C5 | Annuler un build en cours (Stop) | Instant du clic vs instant de l'arrêt du rendu |
| C6 | `--info` dans Settings > Compiler > Command-line Options, refaire C2 | Effet sur le volume et la fluidité |

## 3. Relevés hors chronomètre (5 minutes)

- **Build Analyzer** (onglet après un build) : durée totale, les 3 tâches
  les plus longues, le temps de téléchargement par dépôt — capture d'écran.
- La **tâche la plus lente** du projet témoin (nom + durée).
- Onglet Sync après sync : le **résumé d'impact des téléchargements**.

## 4. Ce que le protocole alimente

- **C2/C3 vs les cibles** : le délai « verdict affiché → dernière ligne »
  d'Android Studio est LE pendant du constat CodeIDE (lignes 17–32 s après
  la fin) — si Android Studio affiche aussi un écart, la cible « aucune
  ligne > 1 s après BuildFinished » se discute ; s'il est nul, la cible
  tient.
- **C1** : la durée de sync d'ouverture Android Studio sur le même projet
  calera la cible de la sync (et dira si 194 s est un écart d'Android ou
  de CodeIDE).
- **C4/C6** : la fluidité sous déluge référence la cible 60 images/s.
- **C5** : la cible « annulation < 500 ms ».

## 5. Remise

Noter les relevés dans un simple fichier texte (une ligne par point,
`C1-essai1 : 4,2 s`) et le joindre au prochain échange : ils seront
confrontés aux mesures appareil (matrice X1–X10, `DIAGNOSTIC-LOGS.md` §6)
dans le verdict §8 puis intégrés aux cibles de
`REFERENCE-ANDROID-STUDIO.md`.

## Historique

- 2026-11-08 : création (Phase 3).
