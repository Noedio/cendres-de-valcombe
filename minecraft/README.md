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
| `src/client/resources/assets/valcombe/` | Textures originales (générées par `tools/make_assets.py`) |
| `src/test/java/` | Tests JUnit des règles et de la table de jeu |
| `src/gametest/java/` | Test en jeu : partie complète avec captures d'écran |
| `tools/` | Génération des textures et du modpack |

## Compiler, tester, livrer

Il faut Java 25.

| Commande | Effet |
| --- | --- |
| `./gradlew build` | Compile et lance les tests JUnit ; le mod est dans `build/libs/` |
| `./gradlew runClientGameTest` | Joue une partie en jeu et enregistre des captures dans `build/run/clientGameTest/screenshots/` |
| `./gradlew runServer`, `./gradlew runClient` | Serveur et client de développement (Simple Voice Chat inclus) |
| `python tools/make_assets.py` | Régénère les textures |
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
| `/vc nuit`, `/vc finjour`, `/vc suivant` | Meneur | Phases ; passer un réveil bloqué |
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
| `/vc carte retirer siege\|maison`, `/vc carte infos` | Opérateur | Retirer le dernier emplacement, résumé de la carte |
| `/vc reglage <clé> <valeur>` | Meneur | `nuit_maisons`, `nuit_immobile`, `nuit_sombre`, `nuit_muette` (oui\|non), `temps_vote` |
| `/vc grimoire`, `/vc role`, `/vc fiches`, `/vc historique`, `/vc mp <message>` | Selon le cas | Consultation, message au Conteur |
