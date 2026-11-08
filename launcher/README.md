# Launcher Valcombe

Un seul `ValcombeLauncher.exe`, portable : il range tout dans le dossier `ValcombeData` à côté de lui, et fonctionne sans installation.

## Ce qu'il fait

1. Au premier clic sur **JOUER**, il installe Prism Launcher (version portable officielle) dans `ValcombeData/prism`. Prism se charge de la connexion au compte Microsoft, gardée en mémoire après la première fois, et du Java de Minecraft.
2. Il lit la dernière version publiée sur GitHub (Réglages → dépôt), puis installe ou met à jour l'instance : Minecraft, Fabric et les mods. Chaque fichier est vérifié par son empreinte.
3. Il applique les options choisies (distance d'affichage, champ de vision, images par seconde, volume, interface, langue, plein écran) et lance le jeu directement sur le serveur.

Pages : Accueil (nouvelles, serveur, compte), Options du jeu, Personnalisation (couleur d'accent, fond, fermeture au lancement), Réglages (dépôt, serveur, mémoire, réparation), Compte.

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
