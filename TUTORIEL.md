# Les Cendres de Valcombe — le tutoriel complet

Du serveur à la dernière nuit : installer, héberger, préparer une soirée, jouer, mener la partie en Conteur, et chaque rôle expliqué.

Pour les joueurs, le [guide du joueur](GUIDE-JOUEUR.md) reprend les règles de la table, rôle par rôle, et ce que le jeu en fait.

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
| **B** | Voix : chuchoter (4 blocs), parler, crier (96 blocs) |
| **V** | Réglages du vocal (choisir son micro) |
| Clic droit sur un trône | S'asseoir (accroupi pour se lever) |
| Plume d'accusation | Viser un joueur et clic droit : l'accuser ; pendant un vote : lever la main |

## 3. Héberger le serveur

### Sur votre PC (pour commencer)

Le plus simple : **depuis le launcher**, page « Héberger une partie ».

1. Cochez « J'ai lu et j'accepte le contrat de licence de Minecraft » (le bouton à côté ouvre le contrat).
2. Cliquez sur **Démarrer le serveur**. La première fois, le launcher télécharge Java, Fabric et les mods du serveur (environ 250 Mo), puis le serveur télécharge Minecraft et crée le monde : comptez une à deux minutes.
3. Quand la pastille passe au vert, donnez à vos amis l'adresse affichée sous « Inviter vos amis » (même box, même Wi-Fi). Ils l'enregistrent dans leur launcher : Réglages › Serveurs enregistrés.
4. Ajoutez le pseudo de chaque ami dans « Invités » : par défaut, le serveur leur est réservé.
5. **Jouer sur mon serveur** lance le jeu et vous connecte chez vous. Avec votre pseudo, vous êtes opérateur : vous pouvez devenir Conteur, lancer les parties et construire.

Le launcher doit rester ouvert pendant la soirée : le fermer arrête le serveur (le monde et la partie en cours sont enregistrés). Tout est rangé dans `ValcombeData/serveur`, à côté du launcher.

Pour que vos amis vous rejoignent **par Internet**, ouvrez sur votre box le port **25565 en TCP** (le jeu) et le port **24454 en UDP** (le vocal) vers ce PC, puis donnez-leur votre adresse publique (bouton « Voir mon adresse publique »). Sans accès à la box : un réseau privé entre amis avec Tailscale, ou un tunnel avec playit.gg (un tunnel TCP et un tunnel UDP).

Pour le faire à la main, sans le launcher :

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

1. **Le village** : la partie se joue dans son monde, « Partie principale ». Au premier démarrage, le serveur y construit le village de Valcombe au centre du sol plat, avec sa salle d'attente. Pour un nouveau village : `/vc carte construire [gothique|monastere|port]` ; réglable : `/vc carte generer <trônes> <petite|normale|grande> <thème> <oui|non> [monde]`. Pour une carte de la bibliothèque : `/vc carte envoyer <id> [monde]` (ancien nom : `charger`). Toutes passent par **« Envoyer à la partie »** : le monde de la partie est **remis à neuf**, plat et vide, puis la carte y est posée au centre. Il ne peut rien rester de l'ancienne carte, quelle que soit sa taille ou ce qu'on a bâti autour : il n'y a plus de rasage à choisir. Chaque monde de partie porte sa propre carte (`[monde]` : une partie d'essai, par exemple).
2. **Annoncer la soirée** : `/vc soiree vendredi 21 h` (visible dans la liste des serveurs et sur les launchers).
3. **Le scénario** (facultatif) : choisi dans l'Atelier ou dans le jeu (menu J › Panneau du Conteur › Éditer le script › Scénarios…). Sans scénario : script « Les Cendres de Valcombe ».
4. **Les figures neutres** (facultatif) : Panneau du Conteur › Figures neutres… (la Sentinelle, le Doyen, le Sablier).
5. **Compte rendu Discord** (facultatif) : `/vc discord <URL du webhook>`.

Les joueurs arrivent dans la **salle d'attente** : la dalle **dorée** pour s'inscrire, la **rouge** pour se désinscrire, l'**obsidienne** pour devenir Conteur (opérateurs). Le mur du fond montre les rôles du script, et le panneau du centre la prochaine partie et les inscrits.

## 5. Le déroulé d'une partie

### Le but

- Le **bien** (Citadins et Étrangers) gagne quand le **Démon meurt**.
- Le **mal** (le Démon et ses Sbires) gagne quand il ne reste que **deux vivants** (sans compter les voyageurs). Avec la règle de la table « le mal gagne dès qu'il est majoritaire », il gagne aussi dès qu'il y a plus de mauvais que de bons parmi les vivants.
- Le mal sait qui il est (à partir de 7 joueurs, ou dès 5 avec la règle de la table « Infos du mal dès 5 ») ; le bien doit deviner qui ment.

### Le lancement

Le Conteur (ou l'opérateur, en Conteur automatique) lance la partie : menu J › Panneau du Conteur › Lancer. Chacun reçoit son rôle : une carte tombe à l'écran et se retourne (touche **K** pour la revoir). Les joueurs sont assis sur leur trône, autour de la place. Sans Conteur humain, la première nuit tombe d'elle-même 20 secondes plus tard.

### La nuit

- Le Conteur fait tomber la nuit : brume, lanternes bleues, chacun dort dans sa maison (selon les réglages : écran noir, micros coupés).
- La **première nuit**, les Sbires apprennent qui est le Démon, et le Démon apprend ses Sbires et trois rôles absents pour bluffer (à partir de 7 joueurs, ou dès 5 avec la règle « Infos du mal dès 5 »).
- Puis chaque rôle qui agit la nuit est **réveillé à son tour** : un écran s'ouvre (« Tu te réveilles »), on désigne un ou deux joueurs en cliquant sur leurs jetons, ou on lit son information, puis « Je me rendors ». Le Conteur et le joueur réveillé se parlent seuls au vocal.
- Ceux qui dorment voient « Tu dors… » : ils peuvent relire leur carnet (touche J).

### Le jour

1. **L'aube** : la cloche sonne, le glas annonce les morts de la nuit (leur jeton montre un crâne).
2. **La discussion** : on parle sur la place, on va **chuchoter** (menu J, le village voit qui chuchote à qui), on **parle en privé** (invitation, puis vocal à deux), on **annonce un rôle** (vrai ou bluff, affiché sous son nom), on fait des **signes** (G). La touche **B** change la portée de la voix. Les maisons et les **recoins** entre les maisons sont des salles privées ; dans un recoin, votre nom ne se voit pas depuis la place.
3. **Le Conseil** : le Conteur rassemble le village (cloche) et ouvre le Conseil. Pendant le Conseil, tout le village s'entend ; en mode « chuchoter », seuls vos deux voisins de trône vous entendent. Les morts se taisent, sauf si un vivant leur donne la parole (menu J › Donner la parole…). Les joueurs brillent à travers les murs.
4. **Accuser** : plume d'accusation en main, viser un joueur (même de loin), clic droit. Chacun accuse au plus une fois par jour, et chacun ne peut être accusé qu'une fois par jour. Toutes les accusations se font d'abord, sans vote ni défense.
5. **Les votes** : quand le Conteur clôt les accusations (ou à la fin du minuteur du Conseil), on vote pour chaque accusé, dans l'ordre des accusations.
6. **Le vote à l'horloge** : une aiguille dorée fait le tour du cercle à partir du voisin de l'accusé. Quand elle passe devant vous, votre main est comptée si elle est levée. Un bandeau montre l'ordre de passage, les mains comptées et quand l'aiguille arrive à vous.
7. **Le billot** : il faut au moins la moitié des vivants (ou plus de la moitié, avec la règle de la majorité absolue). Celui qui a le plus de voix (sans égalité) est « sur le billot ». Le tableau du jour (en haut à droite) montre chaque accusation, ses voix et **qui a voté**.
8. **La fin du jour** : le Conteur clôt la journée ; le joueur sur le billot est exécuté (le gibet sort de terre). Puis la nuit tombe.

Le Conteur peut revenir à l'ancien déroulé (accusation, défense, puis vote, une à la fois) : Panneau du Conteur › Réglages › « Accuser puis voter : non ».

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

Trente secondes plus tard, ou dix secondes après la fin de la veillée si elle dure davantage (tout de suite si le Conteur arrête la partie), la **carte est remise en état** : chacun est ramené à la salle d'attente, puis les blocs cassés ou posés, les lanternes, le contenu des coffres et les objets tombés au sol retrouvent leur état d'avant la partie, par petits lots (une barre montre l'avancement). Une carte faite à la main revient exactement comme elle était, même si elle n'a jamais été enregistrée. Si le serveur s'arrête entre-temps, la remise en état reprend au redémarrage. Pour s'en passer : Panneau du Conteur › Réglages › « Carte remise en état », ou `/vc reglage remise_en_etat non`.

## 6. Mener la partie : le Conteur

**Conteur automatique** : le jeu fait tout (nuits, informations, ouverture des accusations). Le meneur lance la nuit et clôt la journée.

**Conteur humain** : un opérateur devient Conteur (dalle d'obsidienne ou menu). Il ne joue pas, il mène :

- **Pupitre du Conteur** (menu J) : tout ce que le Conteur peut faire, rangé par moment de la partie.

  | Onglet | Ce qu'on y trouve |
  | --- | --- |
  | Partie | Rôles affichés au-dessus des joueurs, accueillir un voyageur, réglages de la nuit, journal, note de partie, pause |
  | Nuit | Faire tomber la nuit, **nuit pas à pas**, étape précédente, passer l'étape, pause |
  | Jour | Rassembler, discussion minutée, **prolonger d'une minute**, ouvrir ou fermer les accusations, lancer ou **annuler le vote**, **gracier** le joueur sur le billot, clore la journée, **ma voix à tous** |
  | Parole | Donner la parole au suivant, tour de table, temps par orateur, arrêter |
  | Fin | Journal, **victoire du bien**, **victoire du mal**, arrêter sans révéler (un second clic confirme) |

- **Nuit pas à pas** : l'ordre complet de la nuit, avec l'étape en cours ; la carte de l'étape (qui se réveille, ce qu'il faut faire et dire, les mises en garde : ivre, empoisonné, mort, déconnecté) ; et les gestes à la main : réveiller ou rendormir un joueur, lui demander de désigner quelqu'un, lui montrer une information libre, poser un jeton de rappel.
- **Jetons de rappel** : chaque rôle en jeu a les siens (« protégé », « faux présage », « a tiré »…), plus des jetons communs. Un clic sur la fiche d'un joueur pose ou retire un jeton ; « Autre rappel » en écrit un libre.
- **Reprendre la main** : la **pause** gèle les minuteurs, la nuit et le vote ; un vote annulé rend à l'accusateur et à l'accusé le droit d'accuser et d'être accusé ; « étape précédente » rejoue un réveil (ses effets déjà appliqués ne sont pas annulés).
- **Joueur déconnecté** : le Conteur est prévenu et peut le **faire relayer** par un joueur fictif jusqu'à son retour. À son retour, le joueur retrouve sa place, son rôle et ce qu'on attendait de lui. En partie automatique, un joueur parti pendant son choix de nuit a trente secondes pour revenir.
- **Temps de parole** : un joueur demande la parole (menu J, ou `/vc parole`) ; le Conteur la donne, ou lance un tour de table. Une barre montre le temps de l'orateur, une cloche sonne à la fin. En partie automatique, la file avance toute seule.
- **Grimoire** (lutrin au centre de la place, ou panneau) : le vrai rôle de chacun, qui est empoisonné, protégé, le faux présage ; sur chaque siège : tuer, ressusciter, empoisonner, changer le rôle, poser une note ou un **rappel**.
- **Au-dessus de chaque joueur**, le Conteur voit son nom et son rôle, en bleu (bien) ou rouge (mal), à travers les murs (désactivable dans son panneau).
- **Bâton du Conteur** : viser un joueur affiche son rôle, son état et ses rappels ; clic droit : sa ligne du grimoire ; accroupi + clic droit : un **aparté** (vocal seul à seul) ; avant la première nuit, ce même geste **convoque** le joueur.
- **Salle de convocation** : avec un Conteur humain, les rôles ne tombent plus tous d'un coup au lancement. Le Conteur reçoit chaque joueur dans une salle à l'écart : « Convoquer le suivant » (onglet Nuit du pupitre, ou `/vc convoquer suivant`) y amène le Conteur et le joueur suivant du cercle, face à face ; le joueur découvre son rôle, le Conteur le lui explique, et personne d'autre ne les entend. Le bouton suivant le renvoie sur son trône et amène le prochain ; « Renvoyer au village » (`/vc convoquer fin`) termine. Faire tomber la nuit remet d'un coup les rôles restants. On peut aussi convoquer n'importe qui en plein jour (`/vc convoquer <joueur>`, ou le bouton de sa fiche). Réglage « Rôles remis à l'écart » pour revenir à la distribution immédiate. Un village généré a sa salle ; sur une carte faite à la main, posez-la avec l'outil « salle de convocation » (sans salle, l'entretien se fait sur place, en vocal privé).
- **Les informations de nuit** : le Conteur relit chaque information avant l'envoi et peut la modifier (rôles ivres, empoisonnés, faux-semblants). Quand une règle laisse le choix au Conteur (Mouchard, Excommunié, Châtelain), la nuit se met en pause et lui pose la question.
- **Bandeau de nuit** : qui est réveillé, et ce qu'on attend. Les joueurs déconnectés sont signalés.
- **Tablette** : `/vc tablette` donne un lien pour mener la partie depuis un téléphone ou une tablette. On y retrouve la nuit pas à pas, les jetons de rappel, la pause, l'annulation du vote, le journal filtré par nuit et par jour, et la modification d'une information avant son envoi.
- **La partie est enregistrée** à chaque moment clé, chaque minute et à l'arrêt du serveur : elle reprend toute seule au redémarrage, avec ses figures, son scénario, ses rappels et ses notes.

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
| **L'Héritière** | Suivantes (3) | S'il reste au moins 5 vivants et que le Démon meurt, elle devient le Démon (sans condition avec la règle de la table « Héritière sans seuil »). | La relève : restez discrète. |
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

Depuis le launcher : page « L'Atelier », bouton **Ouvrir l'Atelier**. Le launcher démarre le serveur de Valcombe sur votre PC et ouvre l'Atelier dans votre navigateur, sans rien d'autre à installer ; vos créations sont gardées dans `ValcombeData/serveur`. Sur un serveur déjà en ligne, `/vc atelier` (opérateur) donne un lien vers une page web, à ouvrir sur un ordinateur du même réseau (ou par tunnel SSH sur un VPS). Chaque opérateur a son propre lien : on prépare à plusieurs, en même temps.

**L'Atelier dans le jeu** : pour tout ce qui touche aux cartes, plus besoin du navigateur. Menu J › **Atelier** (opérateurs), ou `/vc atelier jeu`. Six onglets :

- **Cartes** : la carte actuelle (d'où elle vient, si elle a été retouchée depuis) et la bibliothèque, avec un **aperçu vu de dessus** et ses repères. Enregistrer, enregistrer sous un autre nom, **envoyer à la partie**, renommer, dupliquer, supprimer, partir d'une carte vide (monde neuf), importer un fichier déposé dans `config/valcombe/creations/import`, vider le monde.
- **Village** : le générateur (trônes, taille, thème, maisons).
- **Repères** : entrer ou sortir du mode construction, liste de contrôle de la carte, trône, maison ou centre « ici », **prendre la main**, renuméroter, **annuler** le dernier geste (pose, déplacement, retrait, renumérotation), rebâtir la salle d'attente.
- **Structures** : le catalogue par rayon, avec la **maquette 3D** de la structure choisie (« Voir en grand » pour la tourner) ; « Prendre en main » met la structure dans votre main (et vous fait entrer en mode construction) : son **fantôme** apparaît là où vous visez ; puis tourner, retourner, poser, ranger ; enregistrer la sélection, importer un fichier.
- **La main** (outil « Carte : main ») : clic droit sur un trône, une maison, la salle, le centre ou une **structure posée** depuis le catalogue ouvre un menu : déplacer, tourner (un trône par huitièmes de tour, ou vers le centre ; une structure d'un quart de tour), retourner, retirer. Une structure retirée rend le terrain d'avant. Tout reste annulable.
- **WorldEdit** (dans un atelier) : remplir, remplacer, vider, murs, étendre, copier, couper, coller, tourner, retourner, déplacer, empiler, désélectionner, « en structure » (la sélection rejoint le catalogue), annuler et rétablir, la baguette. Hors des ateliers, l'onglet mène au menu « Mondes ».
- **Créations** : choisir un scénario, lancer une **partie d'essai**, supprimer un scénario ou un rôle créé. Écrire un rôle ou un scénario reste sur la page web (bouton « Page web »).

Ce qui remplace ou supprime quelque chose demande un second clic (« Confirmer »). Ce qui demande du texte (un nom, un bloc) ouvre le chat avec la commande déjà commencée. La réponse du serveur s'affiche en bas de l'écran. L'écran et la page web lancent les mêmes opérations : ce qui est fait d'un côté apparaît de l'autre.

La page web :

- **Accueil** : le parcours pas à pas (rôles, scénario, carte, test, jeu), qui est présent, les dernières modifications.
- **Rôles** : partir d'un exemple ou **d'un rôle intégré** (pour en faire une variante), choisir le camp, l'emblème, le pouvoir (23 modèles), le moment du réveil — par rapport à un rôle intégré ou à un autre rôle créé. Un rôle peut porter **jusqu'à trois effets** (par exemple compter les mauvais et protéger), n'agir qu'**une seule fois par partie** (le joueur choisit sa nuit), agir **de jour** (un tir, des représailles quand on l'accuse), et proposer ses propres **jetons de rappel** au Conteur.
- **Scénarios** : cocher les rôles, la carte, les réglages, les règles spéciales, les figures neutres, un texte d'ouverture. On peut aussi **imposer des rôles** (toujours en jeu), régler la **répartition des camps** par nombre de joueurs, et changer l'**ordre de la nuit**. Règles spéciales : pas d'accusation le premier jour, annonces interdites, conversations privées interdites, chuchotements interdits, morts sans vote, rôle révélé à la mort, nombre d'accusations par jour. **Simuler** 200 parties (avec les règles du scénario : la simulation montre aussi le poids de chaque rôle), lancer une **partie d'essai**, **choisir** pour la prochaine partie.
- **Cartes** : enregistrer la carte actuelle, en **envoyer une à la partie** (le menu « Envoyer à » choisit le monde de partie : celui du serveur, ou une partie d'essai), la **renommer**, la **dupliquer**, envoyer un village généré, **importer une construction** (`.schem` de WorldEdit, `.litematic`, `.nbt`), passer en **mode construction**. Il donne sept outils, dessinés pour Valcombe : cinq pour les repères (trône, maison, centre, salle de convocation, gomme), l'outil de structure (voir plus bas) et la main. Sélectionner, remplir, copier, coller : c'est WorldEdit, dans les ateliers (voir « Construire : WorldEdit dans les ateliers »). Clic droit sur un bloc : poser le repère ; clic droit sur un repère déjà posé : le prendre, puis cliquer là où le reposer (il garde son numéro) ; la gomme le retire. Accroupi avec l'outil maison ou salle : la construction est bâtie d'un coup (la gomme la retire avec son repère). Chaque repère porte une **étiquette** (« Trône 3 », « Maison 3 »…), visible à travers les murs, et un trait de lumière relie le trône et la maison du même numéro quand on s'en approche : le joueur du trône 3 dort dans la maison 3. Le **plan vu de dessus** de l'Atelier montre la carte telle qu'elle est (toits, place, chemins) avec ses repères colorés et numérotés et les traits trône ↔ maison ; on y **ajoute, déplace et retire** les repères à la souris, on les renumérote dans le sens des aiguilles d'une montre, « Aller le voir dans le jeu » vous y téléporte, et « Bâtir une maison ici » ou « Bâtir la salle ici » élève la construction sur le repère choisi. « Annuler la dernière modification des repères » revient sur le dernier geste, quel qu'il soit. Ce qui est fait dans le jeu apparaît sur le plan, et inversement. La page dit quelle carte est en place (son nom dans la bibliothèque, et si elle a été retouchée depuis) et annonce la fin d'un chantier. « Nouvelle carte vide (monde neuf) », « Rebâtir la salle d'attente » et « Vider le monde (jouer sans carte) » s'y trouvent aussi. Envoyer une carte, un village ou une construction se fait **par petits lots** : le serveur reste fluide, une barre montre l'avancement, et la partie ne peut pas commencer avant la fin. Chaque thème a son monument et son décor : puits couvert et étals (gothique), jardin de cloître, carrés de simples et mur d'enceinte (monastère), fanal, cargaisons et chenal (port). Plusieurs opérateurs peuvent construire en même temps.
- **Envoyer à la partie** : c'est le seul chemin pour changer la carte d'un monde de partie (`/vc carte envoyer`, `charger`, `construire`, `generer`, `importer`, `nouvelle`, `vider`, l'Atelier web et l'Atelier en jeu). Dans l'ordre : refus en clair si une partie s'y joue (on change de carte entre deux parties), si un chantier ou une autre carte y est en cours ; si la carte actuelle a été **retouchée à la main** (mode construction, WorldEdit, repères, blocs posés ou cassés) sans être enregistrée depuis, elle est d'abord **sauvegardée dans la bibliothèque**, toute la zone bâtie autour de la place et sur toute sa hauteur, sous le nom « Sauvegarde du … » (si c'est impossible, rien ne bouge) ; les joueurs présents sont envoyés **au hub** ; le monde est **remis à neuf** (ses fichiers effacés, il revient plat et vide) ; la carte est posée au centre par petits lots, une barre suit l'avancement pour ceux qui attendent au hub ; ses trônes, maisons, centre et salle de convocation deviennent le village de la partie. Un message annonce la fin, avec un lien **« [Y aller] »** pour revenir. Seules les cinq dernières sauvegardes automatiques sont gardées : renommez celle que vous voulez conserver, elle devient une carte comme les autres. `/vc carte nouvelle` donne un monde neuf avec une carte vide au centre ; `/vc carte vider` le laisse plat et vide, et l'on joue sans carte, autour du Conteur (le serveur n'y rebâtit rien au démarrage). `/vc carte poser` reste pour les autres mondes (ateliers, parties d'essai) : dans le monde de la partie, il renvoie à `envoyer`. Un repère ne se pose pas à plus de 192 blocs de la place.
- **Éditeur de carte** (en mode construction, opérateurs, jamais pendant une partie ; rien d'autre à installer) :
  - **Annuler, rétablir** : `/vc annuler [fois]` et `/vc retablir [fois]` reviennent sur les poses de structures et les modifications des repères, dans l'ordre, avec les modifications WorldEdit (voir plus bas), jusqu'à 40 niveaux, contenu des coffres compris. L'historique des structures est remis à zéro quand on change de carte ou qu'une partie commence.
  - **Catalogue de structures** : `/vc structure liste` montre les structures fournies (maisons des trois thèmes, salle de convocation, pavillon, puits, cloître, fanal, fontaine, estrade, arche, étals, arbres, lampadaires, banc…) et les vôtres. `/vc structure poser <id>` en prend une en main : avec l'outil « Carte : structure », un contour bleu suit le bloc visé ; **clic droit** pour la poser, **accroupi + clic droit** pour la tourner d'un quart de tour, **clic gauche** pour la retourner (`/vc structure tourner`, `miroir`, `ici` font la même chose sans l'outil). Une maison pose son repère de maison avec elle, la salle de convocation le sien.
  - **Vos structures** : `/vc structures depuis-selection <id> "<nom>" [catégorie]` (ou `/vc structure enregistrer <id> <nom>`) range la sélection WorldEdit dans le catalogue : tenez-vous dedans, à l'endroit qui devra se trouver sur le bloc visé (sinon ce sera le milieu de sa base). Une sélection d'une autre forme que le pavé (polygone, ellipsoïde, cylindre) ne garde que ses propres cases ; `/vc structure repere <id> maison` lui fait poser un repère de maison. Pour **importer un fichier** `.schem`, `.litematic` ou `.nbt` comme structure (sans toucher à la carte), déposez-le dans `config/valcombe/creations/import` puis `/vc structure importer <fichier>`. `renommer`, `supprimer` et `recharger` complètent. Fichiers : `config/valcombe/creations/structures`.
  - Une construction ou une carte venue d'un fichier est relue bloc par bloc : rien n'est posé hors de ses dimensions, ses entités sont ignorées, et les blocs de commande, générateurs et commandes au clic des pancartes arrivent vides.
- **Structures** (page web) : le catalogue, « Prendre en main », tourner, retourner, poser, annuler et rétablir (l'« annuler » commun), enregistrer la sélection WorldEdit, importer un fichier.
- **Scénario lié à une carte** : le choisir charge sa carte à la place de celle du serveur — la page et le jeu le demandent d'abord —, sauf si c'est déjà elle, intacte, qui est en place. Une **partie d'essai** dont la carte se charge démarre d'elle-même à la fin du chantier (`/vc atelier essai <scénario> [joueurs]`).
- **Liens de l'Atelier** : chaque `/vc atelier` donne un nouveau lien (l'ancien ne marche plus) ; un lien cesse de fonctionner au bout de douze heures, ou dès que son propriétaire n'est plus opérateur. `/vc atelier fermer` ferme le vôtre. `/vc tablette fermer` ne ferme que la tablette du Conteur : l'Atelier des opérateurs reste ouvert.
- **Mots des commandes** : `/vc carte trone` (ancien nom : `siege`), `/vc carte outils` (ancien nom : `baguette`), `/vc carte copier <id> [nouvel_id] [nom]` (ou `dupliquer`) ; les anciens noms marchent toujours. `/vc carte importer <fichier>` prend un fichier du dossier d'import, avec les mêmes contrôles que l'import de la page web.
- **Partage** : un fichier `.valcombe` à envoyer à un autre hôte. Il emporte rôles, scénarios et cartes ; vos structures du catalogue y voyagent aussi, avec les mêmes contrôles de taille à l'arrivée.
- **Groupes de rôles** : sur la page Scénarios, « Ranger la sélection dans un groupe… » range les rôles cochés sous un nom, et « Ranger par : groupe » les présente ainsi. Les groupes partent dans le fichier `.valcombe` (cochez-les dans l'onglet Partage) avec leurs rôles créés ; à l'import, un groupe remplace celui de même nom. Dans le jeu, l'éditeur de script (Pupitre du Conteur › Éditer le script) propose les groupes dans sa colonne de droite : cliquer sur le nom passe au groupe suivant (ses jetons sont soulignés d'or), puis « + » l'ajoute au script, « − » l'en retire, « Seul » ne garde que lui ; « Ranger… » enregistre le script en cours comme groupe. En commande : `/vc script groupe <ajouter|retirer|seulement|ranger|supprimer> <nom>`.

### L'atelier carte : préparer une carte

Chaque carte en travail a son propre monde, l'**atelier carte** : plat, paisible, toujours à midi. On y bâtit avec WorldEdit, on y pose les repères, et la carte s'y enregistre toute seule dans la bibliothèque. **Un seul atelier par carte** : rouvrir une carte déjà en atelier vous y mène.

1. **Ouvrir** (opérateurs) : Menu J › **Mondes** › « Ouvrir une carte… », en trois onglets.
   - **Bibliothèque** : la carte est posée au centre d'un monde neuf, puis vous y êtes conduit.
   - **Carte vierge** : un nom, et un monde plat et vide.
   - **Village généré** : trônes, taille, thème, maisons ; le village de Valcombe est bâti au centre, salle de convocation et salle d'attente comprises.

   On ouvre aussi un atelier par le bouton « Ouvrir dans un atelier » de chaque carte (Atelier web, onglet Cartes de l'Atelier en jeu), par « Générer dans un atelier » (onglet Village), ou par `/vc atelier carte ouvrir <carte>`, `nouvelle <nom>`, `village <trônes> [<taille> <thème> <oui|non> <nom>]`.
2. **Bâtir** : à l'entrée, le mode créatif, la baguette de WorldEdit et les sept outils « Carte : … » (trône, maison, centre, salle de convocation, gomme, structure, main). Ils marchent comme plus haut : clic droit pour poser, prendre ou déplacer un repère ; accroupi pour bâtir une maison ou la salle d'un coup ; la main ouvre le menu d'un repère ou d'une structure posée. `/vc annuler` défait aussi les repères de l'atelier. Les invités de l'atelier y bâtissent et y posent les repères comme les opérateurs.
3. **Voir où l'on en est** : chaque repère porte une étiquette flottante, visible à travers les murs (« ♛ Trône 3 », « ⌂ Maison 3 », « ✉ Salle de convocation », « ✚ Centre de la place »), et un contour au sol ; le bord de la zone de la carte est tracé autour de vous. Au-dessus de la place, un panneau compte les repères (« 12 trônes sur 16, 12 maisons, salle de convocation : oui »), dit si la carte est **jouable** (5 trônes au moins) ou ce qui lui manque, et si elle a changé depuis son dernier enregistrement. Le même compte s'affiche au-dessus de la barre d'objets.
4. **L'écran de l'atelier** : Menu J › Mondes › « Atelier de la carte » (ou `/vc atelier carte`, ou le lien du message d'accueil). À gauche, la fiche : le compte des repères, ce qui manque, les conseils (trônes sans maison, pas de salle de convocation), le dernier enregistrement. À droite :
   - **Enregistrer** : la carte va dans la bibliothèque (toute la zone bâtie autour de la place, repères compris) et une **version** est gardée. Si rien n'a changé, rien n'est réécrit.
   - **Enregistrer sous…** : une copie sous un autre nom ; l'atelier continue sur la copie, l'originale reste telle qu'enregistrée.
   - **Versions…** : les enregistrements gardés ; « Revenir à cette version » la remet dans la bibliothèque et dans l'atelier. L'état présent est d'abord gardé, lui aussi : un retour en arrière se défait.
   - **Envoyer à la partie…** : choisissez le monde de partie (la partie principale ou une partie d'essai) ; la carte est enregistrée, puis envoyée (son monde est remis à neuf, comme plus haut). L'atelier reste ouvert. Une carte qui n'est pas jouable n'est pas envoyée.
   - **Fermer l'atelier…** (avec confirmation) : la carte est enregistrée, puis le monde de l'atelier est supprimé ; ceux qui s'y trouvent vont au hub. Une carte vierge où rien n'a été fait n'est pas rangée.

   Ouvrir, enregistrer, envoyer et fermer sont l'affaire des opérateurs.
5. **Enregistrement automatique** : toutes les 5 minutes s'il y a eu du changement, à l'arrêt du serveur et à la fermeture de l'atelier. Chaque carte garde ses 10 dernières versions automatiques et ses 20 dernières versions demandées (enregistrer, fermer, envoyer) : une rafale d'enregistrements automatiques n'emporte jamais une version demandée. Elles sont rangées dans `config/valcombe/creations/cartes/versions/<carte>/`. Une pose coupée par un arrêt du serveur reprend au démarrage.
6. **Commandes** (dans l'atelier) : `/vc atelier carte enregistrer`, `enregistrer-sous <nom>`, `versions`, `revenir <version>`, `envoyer [monde]`, `fermer [confirmer]`, `infos`, `outils` ; de partout, `liste` et `aller <carte>`. Dans un atelier, `/vc carte trone`, `maison`, `centre`, `retirer trone|maison`, `annuler`, `ordonner` et `infos` visent la carte de l'atelier.

### Construire : WorldEdit dans les ateliers

On construit avec **WorldEdit**, livré avec le modpack et le serveur, **dans les ateliers seulement** (atelier carte, atelier structures). Au hub et dans les mondes de partie (« Partie principale » comme les parties d'essai), personne ne s'en sert, opérateurs compris : une commande WorldEdit tapée là-bas reçoit une phrase claire qui dit où aller. On y casse et pose toujours à la main comme avant : l'opérateur en mode créatif, personne d'autre. Poser suit exactement la même règle que casser : un opérateur en survie n'y pose ni bloc ni seau.

- **Qui** : les opérateurs, et les joueurs qu'ils **invitent**. `/vc atelier inviter <joueur> [atelier|tous]` (sans atelier : celui où vous êtes), `/vc atelier retirer <joueur> [atelier|tous]` (sans atelier : toutes ses invitations), `/vc atelier liste`. Un invité voit l'atelier dans Menu J › **Mondes** (« invité à construire ») et y va depuis le hub ; un opérateur y voit qui est invité. Les invitations sont gardées dans `config/valcombe/ateliers.json` et visibles dans l'API d'administration. Un invité n'a jamais ce qui touche au serveur : recharger WorldEdit, changer de monde, scripts, instantanés, effacer ou écrire des fichiers de schémas, défaire le travail d'un autre joueur. Il laisse son inventaire à l'entrée de l'atelier et le retrouve en sortant (même après un redémarrage du serveur) : ce qu'il prend en créatif reste dans l'atelier. Son invitation retirée, il revient au hub.
- **En arrivant** : le mode créatif, et la **baguette de construction** (baguette de WorldEdit dessinée pour Valcombe). En repartant, chacun retrouve son mode d'avant, et la baguette est rendue. `/vc baguette` en redonne une ; celle de `//wand` prend son apparence.
- **La sélection** : clic gauche avec la baguette, premier coin (rouge) ; clic droit, second coin (bleu). Elle s'affiche en 3D : arêtes dorées, quadrillage léger, les trois côtés mesurés (L est-ouest, l nord-sud, h hauteur) et au-dessus « L 12 × l 8 × h 5 » avec le nombre de blocs, lisibles de loin et à travers les murs. Sans la baguette : `//pos1` et `//pos2` (là où vous êtes), `//hpos1` et `//hpos2` (le bloc visé), `//expand <n> [direction]`, `//contract`, `//shift`, `//sel` pour effacer. D'autres formes : `//sel poly`, `//sel ellipsoid`, `//sel cyl`.
- **Les commandes utiles** (elles s'écrivent avec deux barres) :

| Pour | Commande |
| --- | --- |
| Remplir la sélection | `//set <bloc>` (`//set air` la vide ; un mélange : `//set 60%stone_bricks,40%cobblestone`) |
| Remplacer | `//replace <bloc> <par>` |
| Les quatre murs, toutes les faces | `//walls <bloc>`, `//faces <bloc>` |
| Copier, couper, coller | `//copy`, `//cut`, `//paste` (`//paste -a` : sans l'air copié) ; la copie se colle au même endroit par rapport à vous |
| Tourner, retourner la copie | `//rotate 90`, `//flip` (dans la direction du regard) |
| Déplacer, empiler | `//move <n> [direction]`, `//stack <n> [direction]` |
| Lisser, creuser, boucher | `//smooth`, `//hollow`, `//fill <bloc> <rayon>` |
| Formes | `//sphere <bloc> <rayon>`, `//cyl <bloc> <rayon> <hauteur>`, `//pyramid <bloc> <taille>` (`//hsphere`… : creuses) |
| Brosse | `//brush sphere <bloc> <rayon>` sur l'objet en main, clic droit au loin |
| Annuler, rétablir | `/vc annuler [fois]`, `/vc retablir [fois]` (ou `//undo`, `//redo`) |

- **Annuler** : `/vc annuler` défait **dans l'ordre** vos modifications WorldEdit et les gestes de Valcombe (poses de structures du catalogue, repères), chacun par sa propre pile : WorldEdit garde son historique, Valcombe le sien, et Valcombe tient l'ordre des gestes. `//undo`, `/vc carte annuler` et le bouton « Annuler » de l'Atelier marchent toujours : l'ordre suit. Les gestes WorldEdit sont ceux de chacun ; ceux de Valcombe (une seule carte en construction) sont communs aux opérateurs et se défont là où la carte se construit. Une modification des repères annulée ne se rétablit pas : reposez le repère.
- **En faire une structure** : `/vc structures depuis-selection <id> "<nom>" [catégorie]`, ou le bouton « En structure… » de l'onglet WorldEdit de l'Atelier. La structure rejoint « Vos structures », ou le rayon donné en catégorie (`maisons`, `place`, `decor`), et se pose ensuite avec l'outil de structure.
- **Parties d'essai** : WorldEdit y est fermé ; `/vc atelier worldedit-essais oui` l'ouvre aux opérateurs (`non` le referme).

## 9. En cas de souci

| Souci | Solution |
| --- | --- |
| Bandeau rouge « autre version du mod » | L'hôte met à jour le mod du serveur et le redémarre. |
| « Argument incorrect pour la commande » | Même cause : serveur pas à jour. |
| Personne ne s'entend | Port **24454/UDP** fermé, ou micro mal choisi (touche V). |
| « Windows a protégé votre ordinateur » | Informations complémentaires › Exécuter quand même. |
| Message rouge « La fiche du village est illisible » ou « Un chantier a été interrompu » à la connexion d'un opérateur | Le serveur n'a rien rebâti de lui-même, pour ne rien poser sur une carte à moitié faite. Envoyez de nouveau une carte à la partie (`/vc carte envoyer <id>`) ou un nouveau village (`/vc carte construire`) : le monde est remis à neuf d'abord. |
| Le jeu plante | Launcher › Réglages › Rapport de plantage : une archive sur le Bureau, à envoyer à l'hôte. |
| Le launcher n'est pas dans le menu Démarrer | Lancez-le une fois depuis son dossier : il crée ses raccourcis. |
