# Les Cendres de Valcombe — le tutoriel complet

Du serveur à la dernière nuit : installer, héberger, préparer une soirée, jouer, mener la partie en Conteur, et chaque rôle expliqué.

Sommaire :

1. [Ce qu'il faut](#1-ce-quil-faut)
2. [Installer le launcher (chaque joueur)](#2-installer-le-launcher-chaque-joueur)
3. [Héberger le serveur](#3-héberger-le-serveur)
4. [Préparer une soirée](#4-préparer-une-soirée)
5. [Le déroulé d'une partie](#5-le-déroulé-dune-partie)
6. [Mener la partie : le Conteur](#6-mener-la-partie--le-conteur)
7. [Chaque rôle, et comment il se joue](#7-chaque-rôle-et-comment-il-se-joue)
8. [L'Atelier : créer ses rôles, scénarios et cartes](#8-latelier--créer-ses-rôles-scénarios-et-cartes)
9. [En cas de souci](#9-en-cas-de-souci)

---

## 1. Ce qu'il faut

- **Chaque joueur** : un compte Minecraft Java officiel (achat du jeu ou Game Pass PC), un PC sous Windows (le launcher) ou n'importe quel système (Prism Launcher et le modpack), un micro pour le vocal.
- **L'hôte** : un PC qui reste allumé pendant la soirée, ou un petit serveur loué (VPS). Java 25.
- **De 5 à 15 joueurs**, plus un Conteur (un joueur qui mène la partie) ou le Conteur automatique.

## 2. Installer le launcher (chaque joueur)

1. Téléchargez `ValcombeLauncher.exe` sur la page des versions du dépôt GitHub.
2. Rangez-le dans un dossier qui ne bouge pas, par exemple `%LOCALAPPDATA%\Programs\Les Cendres de Valcombe\` (collez ce chemin dans la barre d'adresse de l'Explorateur).
3. Lancez-le. Si Windows affiche « Windows a protégé votre ordinateur » : **Informations complémentaires**, puis **Exécuter quand même** (le launcher n'est pas signé ; Windows ne le redemande plus).
4. Le launcher se range tout seul dans le **menu Démarrer** et pose un raccourci sur le **Bureau**.
5. Onglet **Compte** : connectez votre compte Microsoft (une seule fois).
6. Accueil : choisissez le serveur (celui de la version publiée, ou ajoutez-en un dans Réglages › Serveurs enregistrés), puis **JOUER**.

Le launcher se met à jour tout seul et met le jeu à la dernière version dès son démarrage. Vos touches et réglages sont gardés, même après une réinstallation. Page **Guide** : les règles et les touches ; page **Mon carnet** : vos parties.

**Graphismes** : le launcher installe de quoi rendre le jeu plus beau et plus fluide, côté joueur seulement (rien à faire sur le serveur) :

- **Sodium** (fluidité), **EntityCulling** (rendu allégé), **Continuity** (blocs sans jointures), **LambDynamicLights** (une torche en main éclaire autour de vous) ;
- des **textures haute définition** (Faithful 64x) ;
- des **shaders** (Iris + Complementary Reimagined) : ombres, lumière du soleil, eau et ciel réalistes.

Page **Personnalisation › Graphismes du jeu** : décochez les shaders si le jeu saccade (carte graphique ancienne ou PC portable), ou les textures HD si vous préférez l'aspect d'origine. C'est appliqué au lancement suivant. En jeu, la touche **O** ouvre les réglages des shaders (Iris).

**Touches en jeu** :

| Touche ou geste | Effet |
| --- | --- |
| **J** | Menu du jeu : votre rôle, vos actions, votre carnet |
| **H** | Lever ou baisser la main pendant un vote |
| **G** | Faire un signe au-dessus de sa tête (oui, non, question…) |
| **K** | Revoir sa carte de rôle |
| **V** | Réglages du vocal (choisir son micro) |
| Clic droit sur un trône | S'asseoir (accroupi pour se lever) |
| Plume d'accusation | Viser un joueur et clic droit : l'accuser ; pendant un vote : lever la main |

## 3. Héberger le serveur

### Sur votre PC (pour commencer)

1. Installez **Java 25** (Temurin : https://adoptium.net).
2. Récupérez le dossier `serveur-valcombe` (dans le projet : `minecraft/dist/serveur-valcombe/`, produit par `python tools/make_modpack.py`).
3. Téléchargez le lanceur de serveur Fabric pour Minecraft 26.3 sur https://fabricmc.net/use/server/ et placez-le dans le dossier sous le nom `fabric-server-launch.jar`.
4. Lancez `demarrer.bat` une première fois : il crée `eula.txt`. Lisez le contrat (https://aka.ms/MinecraftEULA) et mettez `eula=true` si vous l'acceptez.
5. Relancez `demarrer.bat`. Dans la console : `whitelist add <pseudo>` pour chaque ami, et `op <votre pseudo>`.
6. Ouvrez sur votre box : **25565 en TCP** (le jeu) et **24454 en UDP** (le vocal). Sans accès à la box : playit.gg (un tunnel TCP et un tunnel UDP).
7. Donnez à vos amis votre adresse publique (ou celle du tunnel).

### Sur un serveur loué (VPS)

Le serveur reste allumé sans votre PC. Il faut un VPS Linux (Debian ou Ubuntu), 2 cœurs rapides et 4 à 8 Go de mémoire. Le guide pas à pas est dans [HEBERGEMENT.md](HEBERGEMENT.md) :

1. installer Java 25 ;
2. envoyer le dossier `serveur-valcombe` (avec `scp`) ;
3. accepter le contrat, puis installer le service `valcombe.service` (redémarrage automatique) ;
4. ouvrir les ports **25565/tcp** et **24454/udp** (et pas le 25570) ;
5. sauvegardes avec `sauvegarder.sh`.

**À chaque nouvelle version** : remplacez le mod dans `mods/` (`valcombe-x.y.z.jar`) et redémarrez le serveur. Sinon, les joueurs voient un bandeau rouge « Le serveur a une autre version du mod ».

**Atelier et tablette sur un VPS** : sans ouvrir le port 25570, avec un tunnel SSH depuis votre PC : `ssh -L 25570:localhost:25570 utilisateur@adresse-du-vps`, puis le lien avec `localhost`.

## 4. Préparer une soirée

En opérateur, dans le jeu :

1. **Le village** : au premier démarrage, le serveur construit le village de Valcombe au point d'apparition, avec sa salle d'attente. Pour le refaire ailleurs : `/vc carte construire [gothique|monastere|port]`. Pour un village réglable : `/vc carte generer <trônes> <petite|normale|grande> <thème> <oui|non>`.
2. **Annoncer la soirée** : `/vc soiree vendredi 21 h` (visible dans la liste des serveurs et sur les launchers).
3. **Le scénario** (facultatif) : choisi dans l'Atelier ou dans le jeu (menu J › Panneau du Conteur › Éditer le script › Scénarios…). Sans scénario : script « Les Cendres de Valcombe ».
4. **Les figures neutres** (facultatif) : Panneau du Conteur › Figures neutres… (la Sentinelle, le Doyen, le Sablier).
5. **Compte rendu Discord** (facultatif) : `/vc discord <URL du webhook>`.

Les joueurs arrivent dans la **salle d'attente** : la dalle **dorée** pour s'inscrire, la **rouge** pour se désinscrire, l'**obsidienne** pour devenir Conteur (opérateurs). Le mur du fond montre les rôles du script, et le panneau du centre la prochaine partie et les inscrits.

## 5. Le déroulé d'une partie

### Le but

- Le **bien** (Citadins et Étrangers) gagne quand le **Démon meurt**.
- Le **mal** (le Démon et ses Sbires) gagne quand il ne reste que **deux vivants** (sans compter les voyageurs).
- Le mal sait qui il est (à partir de 7 joueurs) ; le bien doit deviner qui ment.

### Le lancement

Le Conteur (ou l'opérateur, en Conteur automatique) lance la partie : menu J › Panneau du Conteur › Lancer. Chacun reçoit son rôle : une carte tombe à l'écran et se retourne (touche **K** pour la revoir). Les joueurs sont assis sur leur trône, autour de la place.

### La nuit

- Le Conteur fait tomber la nuit : brume, lanternes bleues, chacun dort dans sa maison (selon les réglages : écran noir, micros coupés).
- La **première nuit**, les Sbires apprennent qui est le Démon, et le Démon apprend ses Sbires et trois rôles absents pour bluffer (à partir de 7 joueurs).
- Puis chaque rôle qui agit la nuit est **réveillé à son tour** : un écran s'ouvre (« Tu te réveilles »), on désigne un ou deux joueurs en cliquant sur leurs jetons, ou on lit son information, puis « Je me rendors ». Le Conteur et le joueur réveillé se parlent seuls au vocal.
- Ceux qui dorment voient « Tu dors… » : ils peuvent relire leur carnet (touche J).

### Le jour

1. **L'aube** : la cloche sonne, le glas annonce les morts de la nuit (leur jeton montre un crâne).
2. **La discussion** : on parle sur la place, on va **chuchoter** (menu J, le village voit qui chuchote à qui), on **parle en privé** (invitation, puis vocal à deux), on **annonce un rôle** (vrai ou bluff, affiché sous son nom), on fait des **signes** (G). Les maisons sont des salles privées.
3. **Le conseil** : le Conteur rassemble le village (cloche) et ouvre les accusations. Pendant le conseil, les joueurs brillent à travers les murs.
4. **Accuser** : plume d'accusation en main, viser un joueur (même de loin), clic droit. Chacun accuse au plus une fois par jour, et chacun ne peut être accusé qu'une fois par jour.
5. **La défense** : l'accusé a quelques secondes pour se défendre. On peut déjà lever la main (H ou la plume).
6. **Le vote à l'horloge** : une aiguille dorée fait le tour du cercle à partir du voisin de l'accusé. Quand elle passe devant vous, votre main est comptée si elle est levée. Un bandeau montre l'ordre de passage, les mains comptées et quand l'aiguille arrive à vous.
7. **Le billot** : il faut au moins la moitié des vivants. Celui qui a le plus de voix (sans égalité) est « sur le billot ». Le tableau du jour (en haut à droite) montre chaque accusation, ses voix et **qui a voté**.
8. **La fin du jour** : le Conteur clôt la journée ; le joueur sur le billot est exécuté (le gibet sort de terre). Puis la nuit tombe.

### Les morts

Un mort reste dans la partie : il parle, il écoute, il devient translucide, porte une coiffe (chapeau de deuil ou crâne) et garde **un seul vote** pour toute la fin de la partie (un cierge sur sa coiffe tant qu'il l'a). Il ne peut plus accuser.

### Les voyageurs

Un joueur arrivé en retard rejoint la partie en cours : « Rejoindre en voyageur » (Conteur automatique) ou accueilli par le Conteur. Il s'assoit sur un trône libre, dans un camp choisi par le Conteur ; un voyageur du mal apprend qui est le Démon. Accuser un voyageur demande son **exil** : tout le monde vote, morts compris. Il peut repartir quand il veut.

### Les figures neutres

- **La Sentinelle** : il peut y avoir un Étranger de plus ou de moins.
- **Le Doyen** : les deux premières minutes de chaque jour, les joueurs aguerris (3 parties ou plus) se taisent.
- **Le Sablier** : chaque journée dure dix minutes au plus.

### La fin

Le jeton de chacun se retourne et montre son rôle ; la **veillée** révèle chaque joueur à tour de rôle à l'écran. La chronique de la partie se relit dans le menu (J › Chronique).

## 6. Mener la partie : le Conteur

**Conteur automatique** : le jeu fait tout (nuits, informations, ouverture des accusations). Le meneur lance la nuit et clôt la journée.

**Conteur humain** : un opérateur devient Conteur (dalle d'obsidienne ou menu). Il ne joue pas, il mène :

- **Panneau du Conteur** (menu J) : faire tomber la nuit, passer une étape, rassembler le village, discussion minutée, ouvrir/fermer les accusations, lancer le vote, clore la journée, accueillir un voyageur, réglages.
- **Grimoire** (lutrin au centre de la place, ou panneau) : le vrai rôle de chacun, qui est empoisonné, protégé, le faux présage ; sur chaque siège : tuer, ressusciter, empoisonner, changer le rôle, poser une note ou un **rappel**.
- **Au-dessus de chaque joueur**, le Conteur voit son nom et son rôle, en bleu (bien) ou rouge (mal), à travers les murs (désactivable dans son panneau).
- **Bâton du Conteur** : viser un joueur affiche son rôle, son état et ses rappels ; clic droit : sa ligne du grimoire ; accroupi + clic droit : un **aparté** (vocal seul à seul).
- **Les informations de nuit** : le Conteur relit chaque information avant l'envoi et peut la modifier (rôles ivres, empoisonnés, faux-semblants). Quand une règle laisse le choix au Conteur (Mouchard, Excommunié, Châtelain), la nuit se met en pause et lui pose la question.
- **Bandeau de nuit** : qui est réveillé, et ce qu'on attend. Les joueurs déconnectés sont signalés.
- **Tablette** : `/vc tablette` donne un lien pour mener la partie depuis un téléphone ou une tablette.

## 7. Chaque rôle, et comment il se joue

« Nuit 1 » et « Nuits suivantes » indiquent l'ordre de réveil (plus petit = plus tôt). Un rôle sans réveil agit seul ou de jour.

### Les Citadins (bien)

| Rôle | Réveil | Pouvoir | En jeu, et conseil |
| --- | --- | --- | --- |
| **La Commère** | Nuit 1 (5) | Apprend que l'un de deux joueurs est un Citadin précis. | Un écran donne deux noms et un rôle. Croisez avec les annonces : l'un des deux dit vrai. |
| **Le Greffier** | Nuit 1 (6) | L'un de deux joueurs est un Étranger précis, ou il n'y en a aucun. | Utile pour démasquer un Somnambule ou un Martyr. |
| **L'Inquisiteur** | Nuit 1 (7) | L'un de deux joueurs est un Sbire précis. | Information forte : un des deux est mauvais. Ne la criez pas trop tôt. |
| **L'Aubergiste** | Nuit 1 (8) | Combien de paires de mauvais sont assis côte à côte. | Un nombre : regardez l'ordre des trônes. |
| **La Veilleuse** | Nuit 1 (9), suivantes (6) | Combien de ses deux voisins vivants sont mauvais. | L'information change quand un voisin meurt : notez-la chaque nuit dans le carnet. |
| **L'Astrologue** | Nuit 1 (10), suivantes (7) | Désigne deux joueurs : l'un d'eux est-il le Démon ? Un joueur du bien passe pour le Démon. | Cliquez sur deux jetons. Méfiez-vous du faux présage : un « oui » n'est pas une preuve. |
| **L'Embaumeur** | Suivantes (8) | Apprend le rôle du joueur exécuté dans la journée. | Ne se réveille que s'il y a eu une exécution. |
| **Le Chapelain** | Suivantes (2) | Désigne un autre joueur : le Démon ne peut pas l'atteindre cette nuit. | Protégez une information précieuse. |
| **Le Fauconnier** | Suivantes (5), s'il meurt la nuit | En mourant la nuit, désigne un joueur et apprend son rôle. | Son dernier regard : choisissez bien. |
| **La Pèlerine** | Aucun | La première fois qu'elle est accusée, si l'accusateur est un Citadin, il est exécuté sur-le-champ. | Rien à faire : on l'accuse, l'effet est automatique. |
| **L'Arbalétrier** | De jour, une fois | Désigne publiquement un joueur : s'il est le Démon, il meurt. | Menu J › Tirer. Une seule flèche par partie. |
| **Le Templier** | Aucun | Le Démon ne peut pas le tuer. | Il peut se déclarer pour attirer… ou protéger les autres. |
| **Le Châtelain** | Aucun | À 3 vivants sans exécution, le bien gagne. S'il meurt la nuit, un autre peut mourir à sa place. | En fin de partie, il propose de ne pas exécuter. |
| **Le Sonneur** *(Brumes)* | Suivantes (7) | Désigne deux joueurs : combien sont mauvais. | Deux jetons à choisir chaque nuit. |
| **Le Cartographe** *(Brumes)* | Nuit 1 (8) | À combien de sièges se trouve le Démon, par le plus court côté. | Comptez les trônes autour du cercle. |

### Les Étrangers (bien, mais gênants)

| Rôle | Réveil | Pouvoir | En jeu, et conseil |
| --- | --- | --- | --- |
| **L'Écuyer** | Nuit 1 (11), suivantes (9) | Choisit un maître : il ne peut voter que si son maître vote. | Un vote de trop est signalé au Conteur. |
| **Le Somnambule** | Aucun | Croit être un Citadin, mais ne l'est pas : ses informations sont fausses. | Il ne le sait pas ! Sa carte montre un Citadin. |
| **L'Excommunié** | Aucun | Peut passer pour mauvais, Sbire ou Démon, même mort. | Il attire les soupçons : à lui de convaincre. |
| **Le Martyr** | Aucun | S'il meurt exécuté, son camp perd. | Évitez le billot à tout prix. |

### Les Sbires (mal)

| Rôle | Réveil | Pouvoir | En jeu, et conseil |
| --- | --- | --- | --- |
| **L'Alchimiste** | Nuit 1 (3), suivantes (1) | Empoisonne un joueur : son pouvoir ne marche pas cette nuit et le lendemain. | Visez les rôles d'information. |
| **Le Mouchard** | Nuit 1 (4), suivantes (10) | Consulte le grimoire chaque nuit ; peut passer pour bon. | Il voit tout : guidez votre Démon. |
| **L'Héritière** | Suivantes (3) | S'il reste au moins 5 vivants et que le Démon meurt, elle devient le Démon. | La relève : restez discrète. |
| **Le Margrave** | Aucun | Deux Étrangers de plus sont en jeu. | Son effet joue au tirage. |
| **La Faussaire** *(Brumes)* | Nuit 1 (3), suivantes (1) | Désigne un joueur : s'il reçoit une information cette nuit, elle est fausse. | Brouillez la meilleure source du bien. |

### Le Démon (mal)

| Rôle | Réveil | Pouvoir | En jeu, et conseil |
| --- | --- | --- | --- |
| **La Goule** | Suivantes (4) | Désigne un joueur : il meurt. Si elle se désigne, un Sbire devient la Goule. | Bluffez avec les rôles absents donnés la première nuit. |

### Le Voyageur

| Rôle | Réveil | Pouvoir | En jeu, et conseil |
| --- | --- | --- | --- |
| **Le Voyageur** | Aucun | Arrivé en cours de partie, dans le camp choisi par le Conteur ; vote et accuse ; le village peut l'exiler. | Un voyageur du mal connaît le Démon. |

Les **rôles créés dans l'Atelier** se jouent de la même façon : leur texte est sur la carte de rôle et dans les fiches (menu J › Fiches des rôles).

## 8. L'Atelier : créer ses rôles, scénarios et cartes

`/vc atelier` (opérateur) donne un lien vers une page web, à ouvrir sur un ordinateur du même réseau (ou par tunnel SSH sur un VPS). Chaque opérateur a son propre lien : on prépare à plusieurs, en même temps.

- **Accueil** : le parcours pas à pas (rôles, scénario, carte, test, jeu), qui est présent, les dernières modifications.
- **Rôles** : partir d'un exemple, choisir le camp, l'emblème, le pouvoir (17 modèles), le moment du réveil.
- **Scénarios** : cocher les rôles, la carte, les réglages, les règles spéciales, les figures neutres, un texte d'ouverture. **Simuler** 200 parties, lancer une **partie d'essai**, **choisir** pour la prochaine partie.
- **Cartes** : enregistrer la carte actuelle, en charger une, générer un village, **importer une construction** (`.schem` de WorldEdit, `.litematic`, `.nbt`), passer en **mode construction** (baguette : clic droit = trône, accroupi = maison ; `/vc carte centre`). Plusieurs opérateurs peuvent construire en même temps.
- **Partage** : un fichier `.valcombe` à envoyer à un autre hôte.

## 9. En cas de souci

| Souci | Solution |
| --- | --- |
| Bandeau rouge « autre version du mod » | L'hôte met à jour le mod du serveur et le redémarre. |
| « Argument incorrect pour la commande » | Même cause : serveur pas à jour. |
| Personne ne s'entend | Port **24454/UDP** fermé, ou micro mal choisi (touche V). |
| « Windows a protégé votre ordinateur » | Informations complémentaires › Exécuter quand même. |
| Le jeu plante | Launcher › Réglages › Rapport de plantage : une archive sur le Bureau, à envoyer à l'hôte. |
| Le launcher n'est pas dans le menu Démarrer | Lancez-le une fois depuis son dossier : il crée ses raccourcis. |
