# Launcher Valcombe

Un seul `ValcombeLauncher.exe`, portable : il range tout dans le dossier `ValcombeData` à côté de lui, et fonctionne sans installation.

## Ce qu'il fait

1. Au premier clic sur **JOUER**, il affiche un code (déjà copié) et ouvre la page Microsoft où le saisir. La connexion est gardée chiffrée sur le PC (DPAPI) : on ne la refait pas à chaque fois. Elle passe par l'application Azure « Valcombe Launcher », validée par Microsoft.
2. Il télécharge lui-même Minecraft, ses ressources, le Java officiel de Mojang et Fabric dans `ValcombeData/jeu`, chaque fichier vérifié par son empreinte.

   Le chemin par Prism Launcher reste dans le code en secours, mais n'est plus utilisé.
3. Il lit la dernière version publiée sur GitHub (Réglages → dépôt), puis installe ou met à jour l'instance : Minecraft, Fabric et les mods. Chaque fichier est vérifié par son empreinte.
4. Il applique les options choisies (distance d'affichage, champ de vision, images par seconde, volume, interface, langue, plein écran) et lance le jeu directement sur le serveur.

5. **Héberger une partie** : il installe et démarre le serveur de Valcombe sur le PC de l'hôte (`ValcombeData/serveur` : Java de Mojang, lanceur du serveur Fabric, mods du serveur), suit son journal, donne les adresses à transmettre, tient la liste des invités et l'arrête proprement. L'hôte accepte lui-même le contrat de Minecraft dans la page.
6. **L'Atelier** : un bouton démarre ce serveur si besoin et ouvre l'Atelier dans le navigateur. Le launcher dépose pour cela un lien secret (`config/valcombe-hote.json`), que le mod lit puis efface au démarrage ; ce lien ne vaut que tant que le serveur tourne.
7. **Serveur** : pilote le serveur de ce PC ou un serveur distant enregistré, par l'API d'administration du mod ([docs/ADMIN-API.md](../docs/ADMIN-API.md)). État (version, durée, TPS, mémoire, joueurs), démarrer, arrêter, redémarrer, joueurs connectés (opérateur, expulser), mondes, console en direct avec envoi de commandes, réglages (liste blanche, opérateurs, message d'accueil, prochaine soirée, joueurs max ; port et mémoire pour ce PC). Les sauvegardes y arriveront avec le serveur qui les fait. Un serveur distant est enregistré avec son adresse, le port de son API et sa clé, gardée chiffrée (DPAPI). Le code est dans `Services/Admin` : la page ne parle qu'à `IServerConnection`, que le tunnel SSH implémentera à son tour.

Pages : Accueil (nouvelles, serveur), Guide du joueur, Mon carnet, Héberger une partie, Serveur, L'Atelier, Options du jeu, Apparence (couleur d'accent, ambiance, braises), Réglages (serveurs, mémoire, réparation, avancé), Compte.

Le fond est une illustration originale dessinée par `tools/make_hero.py` (`Assets/hero.png`). Trois vérifications sans fenêtre à manipuler : `ValcombeLauncher.exe --capture <dossier>` (une capture par page, et une par onglet de la page Serveur, avec un serveur fictif), `--verifier-serveur <rapport.txt>` (installe le serveur local sans le démarrer) et `--verifier-admin <rapport.txt>` (essaie le client de l'API d'administration contre un serveur HTTP fictif, sur un port libre de ce PC).

## Premier lancement

L'exe n'est pas signé : Windows SmartScreen affiche « Windows a protégé votre ordinateur ». Cliquez sur « Informations complémentaires », puis « Exécuter quand même » (voir INSTALLATION.md).

## Compiler

Il faut le SDK .NET 9.

```bash
dotnet publish -c Release -o publish
```

L'exe est dans `publish/ValcombeLauncher.exe` (environ 60 Mo, .NET inclus).

Pour vérifier l'interface sans rien télécharger, `ValcombeLauncher.exe --capture <dossier>` enregistre une capture de chaque page, puis se ferme.

## Publier une version

1. Dans `minecraft/` :

   ```bash
   ./gradlew build
   python tools/make_modpack.py --serveur=adresse:port
   ```

2. Créez une version (« release ») sur le dépôt GitHub et joignez-y `dist/valcombe-pack.json`, `dist/valcombe-<version>.jar` et `dist/Valcombe-<version>.mrpack`.
3. Les nouvelles viennent de `minecraft/news.json` et du texte de la version GitHub.

Les launchers de vos amis voient la nouvelle version au démarrage et la téléchargent au prochain JOUER.

## Connexion intégrée

Voir [CONNEXION-MICROSOFT.md](CONNEXION-MICROSOFT.md).
