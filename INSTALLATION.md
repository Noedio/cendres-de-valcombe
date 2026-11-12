# Les Cendres de Valcombe — installation

Chaque joueur a besoin d'un compte Minecraft Java officiel (achat du jeu ou Game Pass PC).

## Pour les joueurs

1. Installez un launcher gratuit qui accepte les modpacks Modrinth :
   - **Modrinth App** : https://modrinth.com/app
   - ou **Prism Launcher** : https://prismlauncher.org
2. Connectez votre compte Microsoft dans le launcher.
3. Importez le fichier `Valcombe-<version>.mrpack` envoyé par l'hôte :
   - Modrinth App : bouton « + » puis « Importer » ;
   - Prism Launcher : « Ajouter une instance », puis « Importer ».

   Le launcher installe Minecraft 26.3, Fabric, Fabric API, Simple Voice Chat et le mod Valcombe, ainsi que les mods
   graphiques des joueurs (Sodium, Iris, Continuity, LambDynamicLights, EntityCulling). Les textures HD (Faithful 64x)
   et les shaders (Complementary Reimagined) sont proposés en option : activez-les dans les packs de ressources et
   dans les réglages d'Iris (touche O). Le launcher Valcombe, lui, les installe et les active tout seul.
4. Lancez l'instance, puis Multijoueur → « Ajouter un serveur », avec l'adresse donnée par l'hôte.
5. En jeu :
   - **J** ou le grimoire « Menu de Valcombe » (clic droit) : menu du jeu ;
   - **V** : réglages du vocal (Simple Voice Chat). Choisissez votre micro au premier lancement ;
   - **clic droit sur un trône** de la place : s'y asseoir (accroupi pour se lever). En partie, chacun a le sien ; cliquer sur celui d'un autre fait scintiller le vôtre ;
   - **H** : lever ou baisser la main pendant un vote, dès que l'accusé commence sa défense ;
   - **plume d'accusation** (donnée en début de partie) : viser un joueur, même à l'autre bout de la place, et faire un clic droit pour l'accuser. Pendant un vote, la plume lève ou baisse la main. La barre au-dessus de l'inventaire indique qui est visé ;
   - en haut à droite, le **tableau du jour** : qui a accusé qui, avec combien de voix, et qui est sur le billot ; pendant le vote, une rangée de cases montre l'aiguille, les mains comptées et quand elle arrivera à vous ;
   - menu → « S'entraîner » : une partie guidée avec des joueurs fictifs, pour apprendre les règles ;
   - menu → « Regarder » : suivre la partie en spectateur ;
   - menu → « Appeler le Conteur » : il est prévenu et peut vous prendre à part (vocal seul à seul) ;
   - menu → « Annoncer un rôle » : le rôle que vous dites être (vrai ou bluff) s'affiche sous votre nom ;
   - menu → « Parler en privé » : invitez un joueur ; une fois accepté, vous ne vous entendez plus qu'à deux, jusqu'à la cloche, la nuit ou un vote ;
   - **G** : faire un signe au-dessus de sa tête (oui, non, question, attention, réflexion, d'accord) ;
   - **K** : revoir sa carte de rôle ;
   - menu → « Préférences… » → « Mode daltonien » : un triangle (mal) ou un rond (bien) s'ajoute aux couleurs des jetons, une flèche aux mains levées ;
   - une fois mort, vous portez une coiffe (chapeau de deuil ou crâne, au choix du Conteur), avec un cierge tant qu'il vous reste votre vote de fantôme.

Le **launcher Valcombe** a aussi une page « Guide » (règles et touches), des préréglages de performance (petite machine, équilibré, haute qualité), un conseil de mémoire selon votre ordinateur, un voyant d'état du serveur avec la **prochaine soirée** annoncée par l'hôte, une page **« Mon carnet »** (parties, victoires, camps, rôles joués, titre ; elle se remplit à chaque passage au salon) et l'affichage de la vitesse et du temps restant des téléchargements (un téléchargement coupé reprend où il s'était arrêté). Vos touches et réglages (graphismes, son, micro du vocal, serveurs) sont gardés en double dans `%APPDATA%\Valcombe\profil` : ils survivent à une réinstallation ou à un launcher déplacé dans un autre dossier, et ce que vous réglez en jeu n'est plus écrasé au lancement suivant.

### Où ranger le launcher

Le launcher est **portable** : un seul fichier, sans installation, qui range le jeu dans un dossier `ValcombeData` à côté de lui. Mettez-le dans un dossier qui ne bouge pas, par exemple `%LOCALAPPDATA%\Programs\Les Cendres de Valcombe\`, puis lancez-le : à partir de la version 0.5.1, il se range lui-même dans le **menu Démarrer** et pose un raccourci sur le **Bureau** (case « Raccourcis » dans Personnalisation). S'il est déplacé, le raccourci du menu Démarrer suit au lancement suivant.

### Au premier lancement du launcher : « Windows a protégé votre ordinateur »

Le launcher n'est pas signé numériquement (un certificat de signature coûte plusieurs centaines d'euros par an). Windows SmartScreen affiche donc un écran bleu au premier lancement :

1. cliquez sur **Informations complémentaires** ;
2. vérifiez que l'application s'appelle bien `ValcombeLauncher.exe` ;
3. cliquez sur **Exécuter quand même**.

Windows ne le redemande plus ensuite, et les mises à jour automatiques du launcher ne le redéclenchent pas. Téléchargez le launcher uniquement depuis la page des versions du dépôt GitHub.

## Pour l'hôte (serveur)

Le plus simple : **depuis le launcher**, page « Héberger une partie ».

1. Cochez « J'ai lu et j'accepte le contrat de licence de Minecraft » (le bouton à côté ouvre le contrat).
2. Cliquez sur **Démarrer le serveur**. La première fois, le launcher télécharge Java, Fabric et les mods du serveur (environ 250 Mo), puis le serveur télécharge Minecraft et crée le monde : comptez une à deux minutes.
3. Quand la pastille passe au vert, donnez à vos amis l'adresse affichée sous « Inviter vos amis » (même box, même Wi-Fi). Ils l'enregistrent dans leur launcher : Réglages › Serveurs enregistrés.
4. Ajoutez le pseudo de chaque ami dans « Invités » : par défaut, le serveur leur est réservé.
5. **Jouer sur mon serveur** lance le jeu et vous connecte chez vous. Avec votre pseudo, vous êtes opérateur : vous pouvez devenir Conteur, lancer les parties et construire.

Le launcher doit rester ouvert pendant la soirée : le fermer arrête le serveur (le monde et la partie en cours sont enregistrés). Tout est rangé dans `ValcombeData/serveur`, à côté du launcher.

Pour que vos amis vous rejoignent **par Internet**, ouvrez sur votre box le port **25565 en TCP** (le jeu) et le port **24454 en UDP** (le vocal) vers ce PC, puis donnez-leur votre adresse publique (bouton « Voir mon adresse publique »). Sans accès à la box : un réseau privé entre amis avec Tailscale, ou un tunnel avec playit.gg (un tunnel TCP et un tunnel UDP).

### À la main, sans le launcher

1. Installez **Java 25** (par exemple Temurin : https://adoptium.net).
2. Sur la machine de développement, fabriquez le modpack et le dossier serveur :

   ```bash
   cd minecraft
   ./gradlew build
   python tools/make_modpack.py
   ```

   Vous obtenez `dist/Valcombe-<version>.mrpack` (à envoyer aux joueurs) et `dist/serveur-valcombe/`.
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

Serveur toujours allumé sur un VPS loué, grimoire sur tablette, compte rendu Discord et statistiques : voir [HEBERGEMENT.md](HEBERGEMENT.md).

## Pour le Conteur

1. En jeu, ouvrez le menu (**J**), puis « Panneau du Conteur ».
2. Le village de Valcombe est bâti tout seul au premier démarrage du serveur : rien à faire. Pour le rebâtir ailleurs, placez-vous sur un terrain dégagé et cliquez sur « Bâtir le village », puis « Confirmer » : il remplace la carte actuelle, qui est retirée du monde.
3. Les joueurs s'inscrivent depuis leur menu. Pour tester seul, ajoutez des joueurs fictifs.
4. « Lancer (automatique) » : le jeu mène tout seul. « Lancer (Conteur humain) » : vous relisez chaque information, et vous pouvez retoucher la distribution avant la première nuit en cliquant sur les jetons du grimoire.
5. « Faire tomber la nuit », puis « Clore la journée » après les votes.

6. Pendant le jour, « Discussion libre (5 min) » lance un minuteur visible de tous.
7. Le **bâton du Conteur** (donné en début de partie) : viser un joueur affiche son rôle, son état et ses rappels ; clic droit ouvre sa ligne du grimoire ; accroupi + clic droit le prend à part (vocal seul à seul, accroupi + clic droit dans le vide pour finir). La nuit, un bandeau indique qui est réveillé et ce qu'on attend. Dans les réglages : « Coiffe des morts » (chapeau, crâne ou aucune).
8. Le lutrin au centre de la place ouvre aussi le grimoire. Sur un siège du grimoire, « Rappel » pose un jeton de rappel (« ivre », « a choisi Bot3 »…).
9. Scripts : « Les Cendres de Valcombe » (classique) ou « Les Brumes de Valcombe », dans l'écran Script.

10. **Atelier** (opérateur) : `/vc atelier` donne un lien vers une page web, à ouvrir sur un ordinateur du même réseau. On y crée :
    - des **rôles** : nom, camp, texte, emblème (bibliothèque de 45 pictogrammes), moment du réveil et pouvoir choisi parmi 23 modèles (informations, protection, poison, mise à mort, immunité, martyr, Étrangers en plus, pouvoir libre joué par le Conteur…) ;
    - des **scénarios** : rôles, carte, réglages, règles spéciales (pas d'accusation le premier jour, annonces ou conversations privées interdites, discussion minutée) et texte d'ouverture. « Simuler » joue 200 parties de joueurs fictifs et donne le taux de victoire de chaque camp ; « Partie d'essai » lance une partie avec des joueurs fictifs ; « Choisir » prépare la prochaine partie ;
    - la **bibliothèque de cartes** : enregistrer la carte actuelle, en charger une autre, générer un village réglable (5 à 20 trônes, petite, normale ou grande place, avec ou sans maisons) ;
    - le **partage** : un fichier `.valcombe` (rôles, scénarios et cartes) à envoyer à un autre hôte, qui l'importe sur la même page.
11. **L'Atelier dans le jeu** (opérateur) : menu J › « Atelier », ou `/vc atelier jeu`. Cartes (aperçu vu de dessus, charger, enregistrer, renommer, dupliquer, supprimer), générateur de village, repères, catalogue de structures, outils de sélection, scénarios et partie d'essai, sans passer par le navigateur. Dans le jeu aussi, écran Script → « Scénarios… » pour choisir un scénario, ouvrir l'Atelier ou passer en **mode construction** (`/vc carte construction` : créatif, sept outils — trône, maison, centre, salle de convocation, gomme, sélection, structure —, une étiquette sur chaque repère, liste de contrôle ; `/vc carte ordonner` renumérote trônes et maisons dans le sens des aiguilles d'une montre). L'éditeur du mode construction est intégré au mod : `/vc edit` (sélection à deux coins, `remplir`, `remplacer`, `vider`, `murs`, `copier`, `couper`, `coller`, `deplacer`, `empiler`, `annuler`, `retablir`) et `/vc structure` (catalogue de structures à poser, tourner et retourner ; `enregistrer` la sélection, `importer` un `.schem`, `.litematic` ou `.nbt` déposé dans `config/valcombe/creations/import`). Commandes de carte : `/vc carte enregistrer <id> <nom>`, `charger <id>`, `supprimer <id>`, `liste`, `generer <trônes> <petite|normale|grande> <thème> <oui|non>`. Il n'y a qu'une carte à la fois dans le monde : en poser une autre retire l'ancienne (sauvegardée d'office dans la bibliothèque si elle a été retouchée à la main), et après chaque partie la carte est remise dans son état d'avant (`/vc reglage remise_en_etat non` pour s'en passer).

12. **Prochaine soirée** : `/vc soiree vendredi 21 h` l'annonce dans la liste des serveurs de Minecraft et sur l'accueil du launcher, avec le nombre d'inscrits (`/vc soiree effacer` pour la retirer).
13. La salle d'attente montre en 3D les rôles du script choisi et un panneau de la prochaine partie (scénario, inscrits, Conteur). En fin de partie, chaque joueur est révélé à tour de rôle (la veillée). Les joueurs fictifs annoncent un rôle (le mal bluffe), accusent et votent d'eux-mêmes : l'entraînement ressemble davantage à une vraie partie.

14. **Voyageurs** : un joueur arrivé en retard rejoint la partie en cours. Conteur humain : `/vc voyageur <joueur> bien|mal` (ou « Accueillir un voyageur » dans le panneau) ; Conteur automatique : le joueur choisit « Rejoindre en voyageur » dans son menu. Le voyageur s'assoit sur un trône libre, vote et accuse ; un voyageur du mal apprend qui est le Démon. Accuser un voyageur demande son **exil** : tout le monde vote, morts compris, sans dépenser son vote de fantôme. Il peut repartir quand il veut (« Quitter Valcombe »). Les voyageurs ne comptent pas pour la victoire du mal à deux vivants.
15. **Figures neutres** (panneau du Conteur → « Figures neutres… », `/vc figure <id> oui|non`, ou dans un scénario), annoncées à tous au début :
    - **La Sentinelle** : il peut y avoir un Étranger de plus ou de moins ;
    - **Le Doyen** : les deux premières minutes de chaque jour, les joueurs aguerris (3 parties ou plus sur le serveur) se taisent au vocal ;
    - **Le Sablier** : chaque journée dure dix minutes au plus ; la cloche la clôt (après le vote en cours).

Commandes utiles : `/vc discussion <minutes>`, `/vc rappel <joueur> <texte>`, `/vc reglage effets reduits` (petites machines), `/vc reglage spectateurs_grimoire oui`, `/vc carte importer <structure>`.

Toutes les actions existent aussi en commandes `/vc` (voir `minecraft/README.md`).
