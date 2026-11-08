# Les Cendres de Valcombe — installation

Chaque joueur a besoin d'un compte Minecraft Java officiel (achat du jeu ou Game Pass PC).

## Pour les joueurs

1. Installez un launcher gratuit qui accepte les modpacks Modrinth :
   - **Modrinth App** : https://modrinth.com/app
   - ou **Prism Launcher** : https://prismlauncher.org
2. Connectez votre compte Microsoft dans le launcher.
3. Importez le fichier `Valcombe-0.2.0.mrpack` envoyé par l'hôte :
   - Modrinth App : bouton « + » puis « Importer » ;
   - Prism Launcher : « Ajouter une instance », puis « Importer ».

   Le launcher installe Minecraft 26.3, Fabric, Fabric API, Simple Voice Chat et le mod Valcombe.
4. Lancez l'instance, puis Multijoueur → « Ajouter un serveur », avec l'adresse donnée par l'hôte.
5. En jeu :
   - **J** ou l'horloge « Menu de Valcombe » (clic droit) : menu du jeu ;
   - **V** : réglages du vocal (Simple Voice Chat). Choisissez votre micro au premier lancement.

## Pour l'hôte (serveur)

1. Installez **Java 25** (par exemple Temurin : https://adoptium.net).
2. Sur la machine de développement, fabriquez le modpack et le dossier serveur :

   ```bash
   cd minecraft
   ./gradlew build
   python tools/make_modpack.py
   ```

   Vous obtenez `dist/Valcombe-0.2.0.mrpack` (à envoyer aux joueurs) et `dist/serveur-valcombe/`.
3. Téléchargez le lanceur de serveur Fabric pour Minecraft 26.3 (Loader 0.20.1) sur https://fabricmc.net/use/server/ et placez-le dans `serveur-valcombe/` sous le nom `fabric-server-launch.jar`.
4. Lancez `demarrer.bat` une première fois. Le serveur s'arrête et crée `eula.txt` : lisez le contrat https://aka.ms/MinecraftEULA, puis mettez `eula=true` si vous l'acceptez.
5. Relancez `demarrer.bat`. Dans la console du serveur, tapez :

   ```
   whitelist add <pseudo>
   op <pseudo>
   ```

   Ajoutez chaque ami avec `whitelist add`. L'opérateur peut devenir Conteur et lancer les parties.
6. Pour que vos amis vous rejoignent depuis Internet, ouvrez deux ports sur votre box :
   - **25565 en TCP** (Minecraft) ;
   - **24454 en UDP** (vocal).

   Sans accès à la box, utilisez **playit.gg**, avec un tunnel Minecraft (TCP) et un tunnel UDP pour le port 24454.

## Pour le Conteur

1. En jeu, ouvrez le menu (**J**), puis « Panneau du Conteur ».
2. Une fois pour toutes, placez-vous sur un terrain dégagé et cliquez sur « Construire le village ici ». Les parties se joueront ensuite sur la place de Valcombe.
3. Les joueurs s'inscrivent depuis leur menu. Pour tester seul, ajoutez des joueurs fictifs.
4. « Lancer (automatique) » : le jeu mène tout seul. « Lancer (Conteur humain) » : vous relisez chaque information, et vous pouvez retoucher la distribution avant la première nuit en cliquant sur les jetons du grimoire.
5. « Faire tomber la nuit », puis « Clore la journée » après les votes.

Toutes les actions existent aussi en commandes `/vc` (voir `minecraft/README.md`).
