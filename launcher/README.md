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
7. **Serveur** : pilote le serveur de ce PC ou un serveur distant enregistré, par l'API d'administration du mod ([docs/ADMIN-API.md](../docs/ADMIN-API.md)). État (version, durée, TPS, mémoire, joueurs), démarrer, arrêter, redémarrer, joueurs connectés (opérateur, expulser), mondes, console en direct avec envoi de commandes, réglages (liste blanche, opérateurs, message d'accueil, prochaine soirée, joueurs max ; port et mémoire pour ce PC). Un serveur distant en accès direct est enregistré avec son adresse, le port de son API et sa clé, gardée chiffrée (DPAPI). Le code est dans `Services/Admin` : la page ne parle qu'à `IServerConnection`, par accès direct (`DirectConnection`) ou par le tunnel SSH d'un serveur loué (`SshConnection`, fiche avec `Access` à `ssh`), qui sait en plus démarrer le serveur et le mettre à jour (`IManagedServer`).
   - **Onglet Parties** (`MainWindow.Parties.cs`) : le tableau des parties du serveur (état, phase, places, Conteur, joueurs dans son monde, spectateurs), « Suspendre » et « Reprendre », la suppression d'une partie d'essai après confirmation, et « Nouvelle partie d'essai » (`GET/POST /admin/parties`, `/admin/parties/suspendre|reprendre|supprimer`).
   - **Onglet Sauvegardes** (`MainWindow.Backups.cs`) : la liste des archives (date, sorte, taille, mondes ; archive abîmée signalée), « Sauvegarder maintenant » suivi phase par phase, le téléchargement d'une archive (dialogue d'enregistrement, réception au fil de l'eau par `IServerConnection.DownloadAsync`, contrôle du zip et de sa fiche par `BackupArchive`), la restauration avec une confirmation qui dit ce qui sera perdu, gardé et inchangé, son suivi à travers le redémarrage jusqu'au compte rendu (et « Revenir à l'état d'avant »), la restauration prévue (redémarrer ou annuler), l'effacement, et les réglages des sauvegardes automatiques. Serveur de ce PC arrêté : les archives de son dossier `backups/`.
8. **Serveur loué (VPS)** : `Services/Ssh` le pilote par SSH (bibliothèque SSH.NET) : connexion par mot de passe ou clé (gardés chiffrés avec DPAPI), empreinte de la clé du serveur confirmée au premier contact puis exigée, détection du système, installation et mise à jour par `Services/Ssh/install-valcombe.sh` (envoyé par SFTP, idempotent, mêmes mods et même Java que le serveur local), service systemd (démarrer, arrêter, redémarrer, journal), et tunnel vers l'API d'administration (`IAdminTunnel`, que `SshConnection` présente à la page Serveur).
   - **Le parcours d'enregistrement** (page Serveur, « Ajouter un serveur », `MainWindow.Vps.cs`) suit `VpsSetup`, une logique sans interface : adresse et port SSH, compte et secret (mot de passe, ou clé privée choisie dans un dialogue, avec sa phrase de passe), première connexion avec l'empreinte SHA256 montrée et expliquée (une clé changée est refusée net, l'ancienne empreinte ne s'oublie qu'après confirmation), examen du système (sudo refusé : le mot de passe sudo se donne sur place), installation (mémoire, ports, options avancées, contrat de Minecraft à cocher, progression et journal en direct), démarrage jusqu'à ce que l'API réponde, premier opérateur et liste blanche (`POST /admin/reglages`), puis la fiche. Modifier une fiche rouvre le même parcours, secrets gardés s'ils restent vides ; la retirer efface son profil, ses secrets et son empreinte. Tout le réseau est attendu hors du fil de l'interface, « Arrêter » interrompt.
   - Un serveur loué fictif (`DemoVpsConnector`, `DemoManagedConnection`) sert aux captures et à `--verifier-vps`, qui joue le parcours de bout en bout, pannes comprises.

   Marche à suivre : [HEBERGEMENT.md](../HEBERGEMENT.md).

Pages : Accueil (nouvelles, serveur), Guide du joueur, Mon carnet, Héberger une partie, Serveur, L'Atelier, Options du jeu, Apparence (couleur d'accent, ambiance, braises), Réglages (serveurs, mémoire, réparation, avancé), Compte.

Le fond est une illustration originale dessinée par `tools/make_hero.py` (`Assets/hero.png`). Vérifications sans fenêtre à manipuler : `ValcombeLauncher.exe --capture <dossier>` (une capture par page ; pour la page Serveur, une par onglet, les sauvegardes en cours, téléchargée, à restaurer et restaurée, le serveur loué en ligne, arrêté et qui démarre, et chaque étape du parcours de serveur loué, pannes comprises, avec des serveurs fictifs), `--verifier-serveur <rapport.txt>` (installe le serveur local sans le démarrer) et `--verifier-admin <rapport.txt>` (essaie le client de l'API d'administration contre un serveur HTTP fictif, sur un port libre de ce PC, sauvegardes comprises : liste, réglages, sauvegarde suivie, téléchargement contrôlé, restauration). Pour le serveur loué : `--verifier-vps <rapport.txt> [--sans-telechargement]` joue le parcours d'enregistrement contre un serveur loué fictif, puis l'installation, la mise à jour et le pilotage du service à blanc avec le vrai script (bash de Git pour Windows, dans un dossier temporaire), et `--essai-vps <rapport.txt> --hote ...` essaie un vrai VPS (voir HEBERGEMENT.md).

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

1. Le numéro du jeu est `version` dans `minecraft/gradle.properties`, celui du launcher `<Version>` dans `ValcombeLauncher.csproj` (il ne change que si le launcher a changé), et la nouvelle de l'accueil s'ajoute en tête de `minecraft/news.json`.
2. Dans `launcher/` : `dotnet publish -c Release -o publish`. `make_modpack.py` annonce le launcher de `publish/ValcombeLauncher.exe` (son numéro et son empreinte) dans le manifeste : publiez-le avant.
3. Dans `minecraft/` :

   ```bash
   ./gradlew build
   python tools/make_modpack.py --serveur=adresse:port
   ```

4. Créez une version (« release ») `vX.Y.Z` sur le dépôt GitHub et joignez-y `dist/valcombe-pack.json`, `dist/valcombe-<version>.jar`, `dist/Valcombe-<version>.mrpack` et `launcher/publish/ValcombeLauncher.exe`. Le texte de la version reprend les notes (`NOTES-<version>.md` à la racine, quand il y en a).
5. Les nouvelles viennent de `minecraft/news.json` et du texte de la version GitHub.

Les launchers de vos amis voient la nouvelle version au démarrage : ils se mettent à jour eux-mêmes si le launcher annoncé est plus récent, et mettent le jeu à jour aussitôt.

## Connexion intégrée

Voir [CONNEXION-MICROSOFT.md](CONNEXION-MICROSOFT.md).
