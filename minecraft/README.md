# Les Cendres de Valcombe — mod Minecraft

Mod Fabric pour Minecraft Java **26.3** : un jeu de déduction sociale à Conteur.

- Règles et rôles : document « Les Cendres de Valcombe — Rôles et règles ».
- Installation pour les joueurs et l'hôte : [INSTALLATION.md](../INSTALLATION.md).
- Avancement : [SPRINTS.md](../SPRINTS.md).

Avec le mod côté client (modpack), les joueurs ont des écrans sur mesure. Sans lui, ils jouent avec des menus coffre et le chat.

## Organisation

| Dossier | Contenu |
| --- | --- |
| `src/main/java/fr/valcombe/rules/` | Moteur de règles : 22 rôles, nuits, votes, victoire, scripts. Aucune dépendance à Minecraft. |
| `src/main/java/fr/valcombe/session/` | Table de jeu : inscriptions, Conteur, invites, joueurs fictifs, vue envoyée au client |
| `src/main/java/fr/valcombe/net/` | Paquets serveur ↔ client |
| `src/main/java/fr/valcombe/mc/` | Serveur : commandes, menus coffre, village, vocal, scripts enregistrés |
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
| `/vc carte construire\|oublier` | Opérateur | Construire le village sous ses pieds, ou l'oublier (sinon il se construit seul au premier démarrage) |
| `/vc carte nouvelle`, `/vc carte siege`, `/vc carte maison` | Opérateur | Carte personnalisée : la place ici, puis chaque siège et chaque maison à sa position |
| `/vc carte retirer siege\|maison`, `/vc carte annuler`, `/vc carte infos` | Opérateur | Retirer le dernier emplacement, annuler la dernière pose, résumé de la carte |
| `/vc carte renommer <id> <nom>`, `/vc carte copier <id> <nouvel_id>` | Opérateur | Renommer ou dupliquer une carte de la bibliothèque |
| `/vc reglage <clé> <valeur>` | Meneur | `nuit_maisons`, `nuit_immobile`, `nuit_sombre`, `nuit_muette` (oui\|non), `temps_vote` |
| `/vc grimoire`, `/vc role`, `/vc fiches`, `/vc historique`, `/vc mp <message>` | Selon le cas | Consultation, message au Conteur |
