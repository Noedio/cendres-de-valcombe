# Recette de la 0.14.0

Branche `refonte-mondes`, 13 nov. 2026. Rien n'est publié.

## Ce qui a été vérifié

- **Tests JUnit** : 456, tous au vert (`./gradlew build --offline`).
- **Launcher** : `dotnet build` sans erreur ; captures de toutes les pages faites depuis une copie de l'exe.
- **Tests en jeu** (`./gradlew runClientGameTest`) : toute la série passe. Le dernier lancement complet a vérifié tous les tests jusqu'à `AtelierHdGameTest` ; `RecetteGameTest` et `WorldsGameTest` ont ensuite été rejoués seuls, au vert.
- **Test de recette** (`RecetteGameTest`) : le parcours complet d'un opérateur, vérifié étape par étape, avec 46 captures `recette-NN` :

| Étapes | Ce qui est vérifié |
| --- | --- |
| 1 à 4 | Arrivée au hub, écran Mondes, tableau des parties, écran Parties |
| 5 à 22 | Atelier en jeu : Cartes, nouvelle carte (village généré), atelier carte, WorldEdit (baguette, sélection 3D, `//set`), repères, enregistrement et versions, « Envoyer à la partie » avec la carte posée sur le monde recréé |
| 23 à 31 | Structures : catalogue, fiche, atelier structures (retouche d'une structure fournie, retour à l'originale), nouvelle structure depuis une sélection |
| 32 à 36 | Parties, rôles et scénarios, scénario choisi pour la partie d'essai |
| 37 à 42 | Deux parties en même temps (une nuit et un jour dans chacune, aucune annonce, titre ni vue qui passe de l'une à l'autre), mise en suspens et reprise |
| 43 à 46 | Sauvegarde puis restauration, fin des deux parties (révélation et chronique) |

## Défauts relevés sur les captures et corrigés au sprint 17

Étiquettes du pupitre et du menu J qui se chevauchaient, menu de l'outil en main en pixels grossiers, titre « Rôles et scénarios » coupé à l'accueil de l'Atelier, vignette vide d'une structure créée, étiquettes de la sélection 3D qui se recouvraient de loin, message du pied de l'Atelier qui restait d'une page à l'autre, retour de l'écran Parties ouvert depuis l'Atelier, nom demandé pour une carte vierge envoyée à une partie, suggestions de commandes en retard après un changement de monde, tableau du hub limité à 6 parties (il passe en pages), logo de la page web, annulation d'un invité qui défaisait les gestes d'un opérateur, vignette d'une partie qui chargeait des tronçons sur le fil du serveur, WorldEdit en anglais, captures du monde cachées par le chat.

## Petites réserves connues

- À 20 joueurs aux noms longs, le menu J et le pupitre raccourcissent beaucoup de noms (le nom entier s'affiche au survol).
- Launcher, onglet Parties : sur une fiche en suspens, « En suspens · Jour 3 » passe à la ligne.
- La date de la nouvelle dans `news.json` est le 13 nov. 2026 : à changer si la publication se fait un autre jour.

## À essayer à la main avec de vrais joueurs

Ce qu'un test automatique ne peut pas couvrir :

- le vocal Simple Voice Chat entre plusieurs vrais clients, avec deux parties en même temps ;
- un vrai serveur loué (installation par la page Serveur du launcher, SSH, sauvegardes et restauration à distance) ;
- un serveur avec LuckPerms (droits WorldEdit des invités) ;
- la mise à jour d'un serveur 0.13 existant : ranger d'abord dans la bibliothèque une carte faite à la main qui n'y est pas encore (voir `NOTES-0.14.0.md`).
