# Les Cendres de Valcombe : mod Minecraft

Mod Fabric pour Minecraft Java **26.3** : un jeu de déduction sociale à Conteur.

- Règles et rôles : le [guide du joueur](../GUIDE-JOUEUR.md) et le [tutoriel](../TUTORIEL.md).
- Installation pour les joueurs et l'hôte : [INSTALLATION.md](../INSTALLATION.md) ; serveur loué : [HEBERGEMENT.md](../HEBERGEMENT.md).
- Avancement : [SPRINTS.md](../SPRINTS.md) et [PLAN-MONDES.md](../PLAN-MONDES.md) ; dernière version : [NOTES-0.14.0.md](../NOTES-0.14.0.md).
- Documentation technique : [docs/](../docs) (API d'administration, atelier carte, atelier structures, interface haute résolution).

Avec le mod côté client (modpack), les joueurs ont des écrans sur mesure. Sans lui, ils jouent avec des menus coffre et le chat.

## Organisation

| Dossier | Contenu |
| --- | --- |
| `src/main/java/fr/valcombe/rules/` | Moteur de règles : 22 rôles, nuits, votes, victoire, scripts. Aucune dépendance à Minecraft. |
| `src/main/java/fr/valcombe/session/` | Table de jeu : inscriptions, Conteur, invites, joueurs fictifs, vue envoyée au client |
| `src/main/java/fr/valcombe/net/` | Paquets serveur ↔ client |
| `src/main/java/fr/valcombe/mc/` | Serveur : commandes, menus coffre, village, vocal, scripts enregistrés, bibliothèque de cartes, ateliers, WorldEdit, API d'administration, sauvegardes |
| `src/main/java/fr/valcombe/mc/worlds/` | Mondes de Valcombe (Fantasy) : registre, sortes de mondes, droits de construction, invitations |
| `src/main/java/fr/valcombe/mc/hub/` | Hub commun : salle d'accueil, portes, tableau des parties, voyages entre les mondes |
| `src/main/java/fr/valcombe/mc/games/` | Plusieurs parties en même temps : une partie par monde de partie, vocal, équipes, fiches du tableau |
| `src/client/java/fr/valcombe/client/` | Client : écrans sur mesure, affichage en jeu, sons |
| `src/client/java/fr/valcombe/client/hd/` | Boîte à outils d'interface haute résolution : écrans nets à la résolution de l'écran, police lisse (voir `docs/UI-HD.md` à la racine) |
| `src/client/resources/assets/valcombe/` | Textures originales (générées par `tools/make_assets.py`, `make_models.py` et `make_tools.py` ; interface haute résolution et polices par `tools/make_ui.py`) |
| `src/test/java/` | Tests JUnit des règles et de la table de jeu |
| `src/gametest/java/` | Test en jeu : partie complète avec captures d'écran |
| `tools/` | Génération des textures et du modpack |

## Compiler, tester, livrer

Il faut Java 25.

| Commande | Effet |
| --- | --- |
| `./gradlew build` | Compile et lance les tests JUnit ; le mod est dans `build/libs/` |
| `./gradlew runClientGameTest` | Joue une partie en jeu et enregistre des captures dans `build/run/clientGameTest/screenshots/` |
| `./gradlew runServer`, `./gradlew runClient` | Serveur et client de développement (Simple Voice Chat inclus) ; le vocal de ces lancements et des tests en jeu prend le port 24499, jamais 24454, 25565 ni 25570 (réglé par `build.gradle` avant chaque lancement) |
| `python tools/make_assets.py`, puis `make_models.py` et `make_tools.py` | Régénèrent les textures en 128 px (moteur de dessin `tools/dessin.py`, sans dépendance) ; `make_assets.py --cadres` redessine aussi le parchemin, le cadre et les boutons |
| `python tools/make_ui.py` | Régénère les textures d'interface haute résolution et les polices déclarées (`--polices` : retélécharge les polices libres) |
| `python tools/make_modpack.py` | Fabrique `dist/Valcombe-<version>.mrpack` et `dist/serveur-valcombe/` |

## Commandes `/vc`

Toutes les actions des écrans existent aussi en commandes. Le « meneur » est un opérateur ou le Conteur.

| Commande | Qui | Effet |
| --- | --- | --- |
| `/vc`, `/vc menu` | Tous | Ouvrir le menu (et recevoir l'horloge « Menu de Valcombe ») |
| `/vc hub`, `/vc monde [liste]`, `/vc monde aller <monde>` | Tous | Aller au hub, voir les mondes, y aller (`principal` : le village de la Partie principale) |
| `/vc monde creer <sorte> <nom>`, `recreer <monde>`, `renommer <monde> <nom>`, `supprimer <monde>` | Opérateur | Gérer les mondes de Valcombe |
| `/vc partie [liste]`, `/vc partie voir\|rejoindre\|regarder\|aller <partie>`, `/vc partie sortir` | Tous | Les parties du serveur : la fiche, rejoindre, regarder en spectateur, aller dans son monde, quitter les gradins |
| `/vc partie creer <nom>`, `/vc partie supprimer <partie>` | Opérateur | Créer ou supprimer une partie d'essai |
| `/vc partie suspendre [partie]`, `/vc partie reprendre [partie]` | Conteur de la partie, opérateur | Mettre une partie en suspens (joueurs au hub), la reprendre |
| `/vc atelier`, `/vc atelier jeu`, `/vc atelier carte …`, `/vc structures …` | Opérateur | Atelier web, Atelier en jeu, atelier carte, atelier structures (voir le tutoriel) |
| `/vc atelier inviter\|retirer <joueur> [atelier\|tous]`, `/vc atelier liste` | Opérateur | Invitations à construire dans les ateliers |
| `/vc annuler [fois]`, `/vc retablir [fois]`, `/vc baguette` | Opérateur, invité | Annuler et rétablir (WorldEdit et gestes de Valcombe), baguette de construction |
| `/vc sauvegarde [maintenant\|liste\|restaurer <nom>\|reglages]` | Console, opérateur de niveau 4 | Sauvegardes du serveur |
| `/vc admin`, `/vc admin cle`, `/vc admin nouvellecle` | Console, opérateur de niveau 4 | API d'administration |
| `/vc rejoindre`, `/vc quitter`, `/vc joueurs` | Tous | Inscriptions |
| `/vc conteur` | Opérateur | Devenir ou cesser d'être le Conteur |
| `/vc bots <n>`, `/vc bots retirer` | Meneur | Joueurs fictifs pour tester |
| `/vc lancer [auto\|humain]`, `/vc arreter` | Meneur | Lancer ou interrompre une partie |
| `/vc nuit`, `/vc finjour`, `/vc suivant`, `/vc precedent` | Meneur | Phases ; passer un réveil bloqué ; rejouer le réveil précédent |
| `/vc pause`, `/vc prolonger [minutes]` | Meneur | Mettre en pause ou reprendre ; ajouter du temps à la journée |
| `/vc vote annuler`, `/vc gracier`, `/vc victoire bien\|mal` | Meneur | Annuler le vote en cours ; gracier le joueur sur le billot ; déclarer la victoire |
| `/vc relayer <joueur>` | Meneur | Un joueur fictif tient la place d'un joueur déconnecté, jusqu'à son retour |
| `/vc reveiller <joueur>`, `/vc rendormir <joueur>` | Meneur | Réveil à la main, la nuit (le joueur parle au Conteur seul à seul) |
| `/vc demander <joueur> [n]`, `/vc montrer <joueur> <texte>` | Meneur | Demander à un joueur de désigner n joueurs ; lui montrer une information libre |
| `/vc rappel <joueur> <texte>\|retirer <texte>\|effacer` | Meneur | Jetons de rappel |
| `/vc parole`, `/vc parole suivant\|tour [s]\|duree <s>\|donner <joueur>\|stop` | Tous, puis meneur | Demander la parole ; temps de parole |
| `/vc diffusion`, `/vc journal`, `/vc note partie [texte]` | Meneur | Voix du Conteur à tous ; journal de la partie ; note de partie |
| `/vc melanger`, `/vc exclure <joueur>` | Meneur | Salon : mélanger les places, retirer un inscrit |
| `/vc nominer <joueur>`, `/vc tirer <joueur>` | Joueurs vivants | Nomination, tir de l'Arbalétrier (bluff possible) |
| `/vc vote oui\|non`, `/vc choisir <joueur>`, `/vc valider`, `/vc dormir` | Joueurs | Réponses aux invites |
| `/vc envoyer [texte]` | Conteur | Envoyer (ou corriger) une information |
| `/vc attribuer <joueur> <rôle> [rôle cru]` | Meneur | Changer un rôle avant la première nuit |
| `/vc presage <joueur>`, `/vc bluffs <r1> <r2> <r3>` | Meneur | Faux présage, bluffs du Démon |
| `/vc consigne enregistrement\|chatelain\|successeur <valeur>` | Meneur | Consignes du Conteur humain |
| `/vc note <joueur> [texte]` | Meneur | Note dans le grimoire |
| `/vc script basculer <rôle>\|complet\|sauver <nom>\|charger <nom>\|supprimer <nom>` | Meneur | Éditeur de scripts |
| `/vc carte envoyer <id> [monde]` (ou `charger <id>`) | Opérateur | « Envoyer à la partie » : le monde de partie (par défaut celui du serveur) est remis à neuf, plat et vide, puis la carte de la bibliothèque y est posée et devient sa carte. Les joueurs présents attendent au hub ; refusé pendant une partie |
| `/vc carte construire [thème]`, `/vc carte generer <trônes> <taille> <thème> <oui\|non> [monde]` | Opérateur | Nouveau village : monde de partie remis à neuf, puis village généré au centre (il se construit seul au premier démarrage) |
| `/vc carte vider [monde]` | Opérateur | Monde de partie remis à neuf et laissé vide : on joue sans carte, autour du Conteur |
| `/vc carte nouvelle [monde]`, `/vc carte trone`, `/vc carte maison` | Opérateur | Carte personnalisée : un monde neuf, la place au centre, puis chaque trône et chaque maison à sa position |
| `/vc carte retirer siege\|maison`, `/vc carte annuler`, `/vc carte infos` | Opérateur | Retirer le dernier emplacement, annuler la dernière pose, résumé de la carte |
| `/vc carte renommer <id> <nom>`, `/vc carte copier <id> <nouvel_id>` | Opérateur | Renommer ou dupliquer une carte de la bibliothèque |
| `/vc carte poser <id> <monde>` | Opérateur | Poser une carte de la bibliothèque au centre d'un monde de Valcombe (atelier, partie d'essai), sur le sol plat (l'ancienne carte du monde est retirée) ; le monde se donne par son identifiant ou son nom, même approché. Pas dans le monde de la partie : sa carte s'y envoie |
| `/vc carte capturer <monde> <id> [nom]` | Opérateur | Ranger dans la bibliothèque la carte que porte un monde de Valcombe : toute la zone bâtie autour de sa place, et ses repères |
| `/vc reglage <clé> <valeur>` | Meneur | `nuit_maisons`, `nuit_immobile`, `nuit_sombre`, `nuit_muette` (oui\|non), `temps_vote` |
| `/vc grimoire`, `/vc role`, `/vc fiches`, `/vc historique`, `/vc mp <message>` | Selon le cas | Consultation, message au Conteur |
