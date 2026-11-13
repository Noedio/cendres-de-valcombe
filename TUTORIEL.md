# Les Cendres de Valcombe : le tutoriel complet

Du serveur à la dernière nuit : installer, héberger, arriver au hub, mener une ou plusieurs parties, préparer ses cartes et ses structures, et chaque rôle expliqué. Ce tutoriel décrit la version 0.14.

- Pour les joueurs, le [guide du joueur](GUIDE-JOUEUR.md) reprend les règles de la table, rôle par rôle, et ce que le jeu en fait.
- Pour héberger sur un serveur loué, pas à pas : [HEBERGEMENT.md](HEBERGEMENT.md).
- Ce qui a changé depuis la 0.13 : [NOTES-0.14.0.md](NOTES-0.14.0.md).

Sommaire :

1. [Ce qu'il faut](#1-ce-quil-faut)
2. [Une première soirée, pas à pas](#2-une-première-soirée-pas-à-pas)
3. [Installer le launcher (chaque joueur)](#3-installer-le-launcher-chaque-joueur)
4. [Héberger le serveur](#4-héberger-le-serveur)
5. [Le hub et les mondes](#5-le-hub-et-les-mondes)
6. [Plusieurs parties en même temps](#6-plusieurs-parties-en-même-temps)
7. [Préparer une soirée](#7-préparer-une-soirée)
8. [Le déroulé d'une partie](#8-le-déroulé-dune-partie)
9. [Mener la partie : le Conteur](#9-mener-la-partie--le-conteur)
10. [Chaque rôle, et comment il se joue](#10-chaque-rôle-et-comment-il-se-joue)
11. [L'Atelier : cartes, structures, parties, rôles et scénarios](#11-latelier--cartes-structures-parties-rôles-et-scénarios)
12. [L'atelier carte : préparer une carte](#12-latelier-carte--préparer-une-carte)
13. [L'atelier structures](#13-latelier-structures)
14. [Construire : WorldEdit dans les ateliers](#14-construire--worldedit-dans-les-ateliers)
15. [Retoucher la carte d'une partie](#15-retoucher-la-carte-dune-partie)
16. [Le serveur au quotidien](#16-le-serveur-au-quotidien)
17. [En cas de souci](#17-en-cas-de-souci)
18. [Aide-mémoire des commandes](#18-aide-mémoire-des-commandes)

---

## 1. Ce qu'il faut

- **Chaque joueur** : un compte Minecraft Java officiel (achat du jeu ou Game Pass PC), un PC sous Windows (le launcher) ou n'importe quel système (Prism Launcher et le modpack), un micro pour le vocal.
- **L'hôte** : un PC qui reste allumé pendant la soirée, ou un petit serveur loué (VPS Linux). Le launcher installe tout ce qu'il faut, Java compris.
- **De 5 à 16 joueurs par partie**, plus un Conteur (un joueur qui mène la partie) ou le Conteur automatique. Un serveur peut faire jouer plusieurs parties en même temps.

## 2. Une première soirée, pas à pas

1. **Chaque joueur** installe le launcher et connecte son compte (partie 3).
2. **L'hôte** ouvre le launcher, page **Héberger une partie**, accepte le contrat de Minecraft et clique sur **Démarrer le serveur** (partie 4). Il ajoute le pseudo de chaque ami dans « Invités » et leur donne l'adresse affichée sous « Inviter vos amis ».
3. **Les joueurs** enregistrent cette adresse (launcher › Réglages › Serveurs enregistrés), la choisissent sur l'accueil, puis **JOUER**. L'hôte clique sur **Jouer sur mon serveur**.
4. **Tout le monde arrive au hub**, la salle d'accueil commune (partie 5). Le village de la **Partie principale** est déjà bâti : le serveur l'a posé à son premier démarrage.
5. **S'inscrire** : marcher sur la dalle **dorée** du hub (elle inscrit à la Partie principale), ou cliquer sur la fiche de la partie au **tableau des parties**, puis **Rejoindre**. Chacun est conduit à la salle d'attente du village.
6. **Le Conteur** (un opérateur, par exemple l'hôte) marche sur la dalle d'**obsidienne**, ou ouvre le menu (**J**) › **Panneau du Conteur** › **Devenir le Conteur**. Sans Conteur humain, le jeu mène seul.
7. **Le scénario** (facultatif) : Pupitre du Conteur › **Éditer le script** › **Scénarios…**, ou Atelier en jeu › **Rôles et scénarios** (partie 7). Sans choix : « Les Cendres de Valcombe ».
8. **Lancer** : Pupitre du Conteur › **Lancer (automatique)** ou **Lancer (Conteur humain)**. Chacun reçoit son rôle et s'assoit sur son trône.
9. **Jouer** : nuits, jours, Conseil, votes (partie 8).
10. **Après la partie**, la carte est remise en état d'elle-même et le salon rouvre : on peut recommencer.

## 3. Installer le launcher (chaque joueur)

1. Téléchargez `ValcombeLauncher.exe` sur la page des versions du dépôt GitHub.
2. Rangez-le dans un dossier qui ne bouge pas, par exemple `%LOCALAPPDATA%\Programs\Les Cendres de Valcombe\` (collez ce chemin dans la barre d'adresse de l'Explorateur).
3. Lancez-le. Si Windows affiche « Windows a protégé votre ordinateur » : **Informations complémentaires**, puis **Exécuter quand même** (le launcher n'est pas signé ; Windows ne le redemande plus).
4. Le launcher se range tout seul dans le **menu Démarrer** et pose un raccourci sur le **Bureau**.
5. Onglet **Compte** : connectez votre compte Microsoft (une seule fois).
6. Accueil : choisissez le serveur (celui de la version publiée, ou ajoutez-en un dans Réglages › Serveurs enregistrés), puis **JOUER**.

Le launcher se met à jour tout seul et met le jeu à la dernière version dès son démarrage. En bas de sa barre latérale, il affiche son numéro et celui du jeu installé. Vos touches et réglages sont gardés, même après une réinstallation. Page **Guide du joueur** : les règles et les touches ; page **Mon carnet** : vos parties.

**Graphismes** : le launcher installe de quoi rendre le jeu plus beau et plus fluide, côté joueur seulement (rien à faire sur le serveur) :

- **Sodium** (fluidité), **EntityCulling** (rendu allégé), **Continuity** (blocs sans jointures), **LambDynamicLights** (une torche en main éclaire autour de vous) ;
- des **textures haute définition** (Faithful 64x) ;
- des **shaders** (Iris + Complementary Reimagined) : ombres, lumière du soleil, eau et ciel réalistes.

Page **Apparence › Graphismes du jeu** : décochez les shaders si le jeu saccade (carte graphique ancienne ou PC portable), ou les textures HD si vous préférez l'aspect d'origine. C'est appliqué au lancement suivant. En jeu, la touche **O** ouvre les réglages des shaders (Iris).

Les écrans de Valcombe sont dessinés à la résolution réelle de votre écran, avec une police lisse : ils restent nets à toutes les tailles d'interface (Options › Taille de l'interface).

**Touches en jeu** :

| Touche ou geste | Effet |
| --- | --- |
| **J** | Menu du jeu : votre rôle, vos actions, votre carnet, les mondes ; au hub, les parties |
| **H** | Lever ou baisser la main pendant un vote |
| **G** | Faire un signe au-dessus de sa tête (oui, non, question…) |
| **K** | Revoir sa carte de rôle |
| **B** | Voix : chuchoter (4 blocs), parler, crier (96 blocs) |
| **V** | Réglages du vocal (choisir son micro) |
| Clic droit sur un trône | S'asseoir (accroupi pour se lever) |
| Plume d'accusation | Viser un joueur et clic droit : l'accuser ; pendant un vote : lever la main |

Les touches de Valcombe se changent dans les options de Minecraft (Commandes › Raccourcis clavier), rubrique « Les Cendres de Valcombe ».

## 4. Héberger le serveur

Trois façons, du plus simple au plus solide : le serveur sur votre PC depuis le launcher, un serveur loué installé par le launcher, ou un serveur monté à la main.

### Les ports

| Port | Protocole | Pour quoi | À ouvrir au public |
| --- | --- | --- | --- |
| **25565** | TCP | Le jeu (port standard de Minecraft) | Oui, pour jouer par Internet |
| **24454** | UDP | Le vocal (Simple Voice Chat) | Oui, pour jouer par Internet |
| **25570** | TCP | Tablette du Conteur, Atelier web et API d'administration | Non : réseau local, ou tunnel SSH |

Le port du jeu se change dans le launcher (page Héberger, ou page Serveur › Réglages › « Port du jeu ») ; celui de la tablette et de l'Atelier avec `/vc tablette port <numéro>`. L'API d'administration n'accepte que les requêtes de la machine du serveur : depuis un autre PC, le launcher passe par un tunnel SSH.

### Sur votre PC, avec le launcher

1. Page **Héberger une partie** : cochez « J'ai lu et j'accepte le contrat de licence de Minecraft » (le bouton « Lire le contrat de Minecraft » l'ouvre).
2. **Démarrer le serveur**. La première fois, le launcher télécharge Java, Fabric et les mods du serveur (Fabric API, Simple Voice Chat, WorldEdit et Valcombe ; environ 250 Mo), puis le serveur télécharge Minecraft et crée ses mondes (le hub, le monde de la Partie principale et son village) : comptez une à deux minutes. Ensuite, chaque démarrage met le serveur à la dernière version publiée.
3. Quand la pastille passe au vert, donnez à vos amis l'adresse affichée sous **Inviter vos amis** (même box, même Wi-Fi). Ils l'enregistrent dans leur launcher : Réglages › Serveurs enregistrés.
4. Ajoutez le pseudo de chaque ami dans **Invités** : par défaut, le serveur leur est réservé.
5. **Jouer sur mon serveur** lance le jeu et vous connecte chez vous. Avec votre pseudo (« Votre pseudo Minecraft », rempli tout seul à votre première connexion), vous êtes opérateur : vous pouvez devenir Conteur, lancer les parties, ouvrir l'Atelier et construire dans les ateliers.

Le launcher doit rester ouvert pendant la soirée : le fermer arrête le serveur (les mondes et les parties en cours sont enregistrés, elles reprendront au prochain démarrage). Tout est rangé dans `ValcombeData/serveur`, à côté du launcher (« Ouvrir le dossier du serveur »).

Pour que vos amis vous rejoignent **par Internet**, ouvrez sur votre box le port **25565 en TCP** et le port **24454 en UDP** vers ce PC, puis donnez-leur votre adresse publique (bouton « Voir mon adresse publique »). Sans accès à la box : un réseau privé entre amis avec Tailscale, ou un tunnel avec playit.gg (un tunnel TCP et un tunnel UDP).

### La page Serveur du launcher

Elle pilote le serveur de votre PC comme un serveur distant. Choisissez-le dans la liste en haut à droite : **Ce PC (le serveur hébergé ici)**, ou un serveur enregistré avec « Ajouter un serveur ».

- **En tête** : l'état (version, durée, TPS, mémoire, joueurs) et les boutons **Démarrer**, **Redémarrer**, **Arrêter** (arrêt propre : les mondes et les parties sont enregistrés) ; pour un serveur loué, **Mettre à jour (vX)** quand une nouvelle version est publiée.
- **Vue d'ensemble** : les joueurs connectés (opérateur, expulser) et les mondes, avec l'état de la partie de chaque monde de partie.
- **Console** : la console en direct, et une ligne pour envoyer une commande (`list`, `say`, `whitelist`, `vc`…).
- **Réglages** : « Qui peut entrer » (liste blanche, opérateurs), « Annonce et capacité » (message d'accueil, prochaine soirée, joueurs au plus, port du jeu ; la mémoire pour ce PC). « Enregistrer » envoie les changements et dit ceux qui demandent un redémarrage.
- **Parties** : le tableau des parties du serveur (partie 6), avec **Suspendre**, **Reprendre**, la suppression d'une partie d'essai (confirmée) et **Nouvelle partie d'essai** › « Créer la partie ».
- **Sauvegardes** : les archives, « Sauvegarder maintenant », le téléchargement, la restauration et les réglages des sauvegardes automatiques (partie 16).

### Sur un serveur loué (VPS)

Le serveur reste allumé sans votre PC. Il faut un VPS Linux avec systemd (Debian 12 ou 13, Ubuntu 24.04), 2 cœurs et 4 Go de mémoire au moins.

1. Page **Serveur** › **Ajouter un serveur** › **Enregistrer un serveur loué**.
2. Suivez les six étapes : **Adresse** (et port SSH), **Compte** (mot de passe ou clé privée), **Empreinte** (comparez-la à celle de votre hébergeur avant de l'accepter), **Système** (le launcher vérifie la machine), **Installation** (mémoire, ports 25565 et 24454, pare-feu, contrat de Minecraft à cocher, puis « Installer »), **Joueurs** (premier opérateur et liste blanche).
3. La fiche s'ajoute à la liste de la page Serveur, avec l'adresse à donner aux joueurs. Tout le reste (console, réglages, parties, sauvegardes, mise à jour) se fait depuis la même page, par un tunnel SSH que le launcher ouvre lui-même : seuls les ports du jeu et du vocal sont ouverts au public.

Le détail de chaque étape, l'installation à la main (sans le launcher) et les messages d'erreur sont dans [HEBERGEMENT.md](HEBERGEMENT.md).

**À chaque nouvelle version**, le serveur doit passer au même mod que les joueurs. Ce PC : arrêtez le serveur, puis démarrez-le depuis le launcher (page Héberger ou Serveur) : chaque démarrage le met à la dernière version publiée (« Redémarrer » le relance tel quel, sans mise à jour). Serveur loué : bouton « Mettre à jour (vX) ». À la main : remplacez le mod dans `mods/` (`valcombe-x.y.z.jar`) et redémarrez. Sinon, les joueurs voient un bandeau rouge « Le serveur a une autre version du mod ».

## 5. Le hub et les mondes

### Le hub

En vous connectant, vous arrivez au **hub**, une salle d'accueil commune à tout le serveur, sous une coupole vitrée. Un joueur assis à une partie en cours, lui, retrouve directement sa place.

- **Les dalles**, au centre : la **dorée** (« S'inscrire ») inscrit à la Partie principale, la **rouge** (« Se désinscrire ») retire l'inscription, l'**obsidienne** (« Devenir Conteur », réservée aux opérateurs) fait de vous le Conteur de la Partie principale. Ce sont les mêmes dalles que dans la salle d'attente de chaque village.
- **Le tableau des parties** : deux panneaux de trois fiches, une par partie (sa phase, ses places en pastilles, ses joueurs, son Conteur). La Partie principale porte un liseré d'or. **Cliquez sur une fiche** : l'écran **Parties** s'ouvre sur elle, pour la rejoindre ou la regarder (partie 6). Au-delà de six parties, le menu J › « Parties » les montre toutes.
- **Les portes**, trois de chaque côté : la première mène au village de la Partie principale, les suivantes aux parties d'essai. Au-dessus de chacune, le nom du monde, ce qui s'y passe et qui s'y trouve (« 3 inscrits sur 16 places · 2 joueurs », « En cours, nuit 2 · 8 joueurs »). Une porte libre attend une prochaine partie.
- **Le mur des rôles**, au nord : les rôles du script de la Partie principale, et le panneau de la prochaine partie.

### Le menu « Mondes »

Menu **J** › **Mondes** : les mondes où vous pouvez aller, avec leur sorte et qui s'y trouve. « Y aller » vous y conduit ; vous y retrouvez votre dernière position (la première fois, son point d'arrivée). En commande : `/vc hub` ramène au hub, `/vc monde` liste les mondes, `/vc monde aller <monde>` y emmène (`principal` : le village de la Partie principale).

| Sorte | Ce que c'est | Qui y va |
| --- | --- | --- |
| Hub | La salle d'accueil commune | Tout le monde |
| Monde de partie | La Partie principale, avec son village ; chaque monde de partie porte sa propre carte | Tout le monde |
| Partie d'essai | Une autre partie, pour répéter une carte ou un scénario sans toucher à la vraie | Tout le monde |
| Atelier carte | Une carte en cours de travail, dans son propre monde (partie 12) | Opérateurs et invités |
| Atelier structures | Toutes les structures, chacune sur sa parcelle (partie 13) | Opérateurs et invités |

Tous ces mondes sont **plats et paisibles** : pas de monstres ; dans les ateliers, pas de météo et toujours midi. Le monde principal de Minecraft ne sert plus.

- Un joueur **assis à une partie en cours** ne quitte pas son monde avant la fin : le bouton « Mondes » disparaît de son menu.
- Pour un opérateur, le menu montre aussi « Ouvrir une carte… » (un nouvel atelier carte) et, dans un atelier carte, « Atelier de la carte ».
- Les opérateurs créent, remettent à neuf, renomment et suppriment des mondes avec `/vc monde creer <sorte> <nom>`, `recreer <monde>`, `renommer <monde> <nom>`, `supprimer <monde>`. Les joueurs d'un monde remis à neuf ou supprimé vont au hub.

## 6. Plusieurs parties en même temps

Chaque partie se joue **dans son propre monde**, avec ses joueurs, son Conteur, son scénario, ses minuteurs, son vocal et sa sauvegarde. Elles sont séparées : on ne voit, n'entend et ne commande que la partie du monde où l'on se trouve.

- **La Partie principale** est créée au premier démarrage, avec le village de Valcombe. Elle ne se supprime pas. Les dalles du hub et la première porte y mènent.
- **Les parties d'essai** : autant qu'on veut. Elles servent à répéter une carte, un scénario, ou à faire jouer deux tables le même soir.

### Créer une partie d'essai (opérateurs)

- Au hub : menu **J** › **Parties**, écrivez son nom dans « Nom de la nouvelle partie d'essai », puis **Créer une partie d'essai**.
- Ou : Atelier en jeu › **Parties** › « Créer une partie d'essai » ; Atelier web, onglet **Parties** ; launcher, page Serveur › onglet **Parties** › « Créer la partie » ; `/vc partie creer <nom>`.

Un monde plat neuf est créé, et le village de Valcombe s'y pose par petits lots (une à deux minutes). Sa porte s'allume au hub et sa fiche apparaît au tableau des parties.

### L'écran « Parties »

Menu **J** › **Parties** (au hub), ou une fiche cliquée au tableau des parties. À gauche, chaque partie avec sa vignette (le ciel de sa phase, ses places), son état (Ouverte, En cours, En pause, En suspens) et ce que vous y êtes (« ta partie », « Conteur », « inscrit »). À droite, sa fiche et ses boutons :

- **Rejoindre** : vous inscrit au salon d'une partie ouverte et vous y conduit. Si vous y jouez déjà, y êtes inscrit ou la contez, le bouton devient **Y retourner** et vous ramène à votre place. Grisé, il dit pourquoi au survol : la partie a commencé (on peut la regarder), le salon est plein, la partie est en suspens, ou une partie en cours ailleurs vous retient.
- **Regarder** : vous entrez dans son monde **en spectateur** (mode spectateur de Minecraft) : vous allez où vous voulez, sans voir les rôles ni entendre le vocal des joueurs. Menu J › **Arrêter de regarder** (ou `/vc partie sortir`) vous ramène au hub.
- **Suspendre** ou **Reprendre** : pour son Conteur et les opérateurs (voir plus bas).
- **Supprimer** (opérateurs, parties d'essai pas encore lancées) : « Confirmer » efface la partie et son monde ; ses joueurs vont au hub.

### Mener une partie parmi d'autres

- Les commandes, le menu J et le Pupitre du Conteur visent **la partie du monde où vous êtes**. Pour mener une autre partie, allez dans son monde (porte du hub, « Mondes », ou `/vc partie aller <partie>`). Au hub, une commande vise votre partie d'attache (celle où vous êtes inscrit, assis ou Conteur), sinon la Partie principale.
- **Le vocal** est séparé par partie : on n'entend jamais une autre table, ni ses groupes privés. Les spectateurs n'entendent pas les joueurs.
- Chaque partie a ses noms au-dessus des têtes et ses équipes ; l'Atelier web a un menu **Partie visée** pour choisir la partie qui reçoit les scénarios et les parties d'essai lancées depuis la page.

### Mettre en suspens, reprendre

Une partie lancée peut être **mise en suspens** : son horloge est gelée (minuteurs, étapes de la nuit, aiguille du vote, joueurs fictifs), elle est enregistrée, et tous les joueurs de son monde vont au hub, leur place gardée. Les autres parties continuent.

- **Suspendre** : Pupitre du Conteur › onglet **Partie** › **Mettre en suspens** (un second clic confirme) ; écran Parties › « Suspendre » ; Atelier en jeu › Parties › fiche de la partie ; launcher › Serveur › Parties ; `/vc partie suspendre [partie]`.
- **Pendant le suspens**, on n'entre plus dans son monde, sauf pour la mener (son Conteur, les opérateurs). Un joueur de cette partie qui se connecte arrive au hub, prévenu.
- **Reprendre** : Pupitre › « Reprendre la partie en suspens », écran Parties › « Reprendre », launcher, ou `/vc partie reprendre [partie]`. Ses joueurs reçoivent un lien **[Y aller]** et retrouvent leur siège ; la partie reste en pause le temps que tout le monde soit revenu (une minute au plus), puis repart d'elle-même.
- **Redémarrage du serveur** : chaque partie est enregistrée à l'arrêt et reprend au démarrage dans son monde, en cours, en pause ou en suspens.

## 7. Préparer une soirée

En opérateur, dans le jeu :

1. **Où jouer** : la Partie principale, ou une partie d'essai (partie 6). Tout ce qui suit se fait dans le monde de cette partie, ou en la choisissant dans l'Atelier.
2. **La carte** : la Partie principale reçoit le village de Valcombe à son premier démarrage. Pour en changer, il n'y a qu'un chemin, **« Envoyer à la partie »** :
   - une carte de la bibliothèque : Atelier en jeu › **Cartes** › fiche de la carte › **Envoyer à la partie…** (choisissez la partie, puis « Confirmer l'envoi ») ; ou `/vc carte envoyer <carte> [monde]` ;
   - un nouveau village : Pupitre du Conteur, au salon › « Thème : … », puis **Nouveau village** et **Confirmer** ; ou Atelier › Cartes › **Nouvelle carte** › Village généré › « Dans une partie » ; ou `/vc carte construire [gothique|monastere|port]`, `/vc carte generer <trônes> <petite|normale|grande> <thème> <oui|non> [monde]`.

   Le monde de la partie est alors **remis à neuf** (plat et vide), puis la carte y est posée au centre. Il ne peut rien rester de l'ancienne carte. Le détail est plus bas (« Envoyer à la partie », partie 11).
3. **Le scénario** (facultatif) : Atelier en jeu › **Rôles et scénarios** › fiche du scénario › **Choisir pour une partie…** ; ou Pupitre du Conteur › Éditer le script › Scénarios… ; ou l'Atelier web. Sans scénario : script « Les Cendres de Valcombe ».
4. **Les figures neutres** (facultatif) : Pupitre du Conteur › Figures neutres… (la Sentinelle, le Doyen, le Sablier).
5. **Les durées** : Pupitre du Conteur › Durées et minuteurs… (discussion, Conseil, vote).
6. **Annoncer la soirée** : `/vc soiree vendredi 21 h`, ou launcher › Serveur › Réglages › « Prochaine soirée ». Elle s'affiche dans la liste des serveurs de Minecraft et sur l'accueil des launchers.
7. **Compte rendu Discord** (facultatif) : `/vc discord <URL du webhook>`.

Les joueurs s'inscrivent au hub (dalle dorée pour la Partie principale, tableau des parties pour toutes) ou dans la **salle d'attente** du village : la dalle **dorée** pour s'inscrire, la **rouge** pour se désinscrire, l'**obsidienne** pour devenir Conteur (opérateurs). Le mur du fond montre les rôles du script, et le panneau du centre la prochaine partie et les inscrits.

## 8. Le déroulé d'une partie

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

Le Conteur peut revenir à l'ancien déroulé (accusation, défense, puis vote, une à la fois) : Pupitre du Conteur › « Réglages de la nuit… » (écran « Réglages de la partie ») › décochez « Accuser puis voter ».

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

Trente secondes plus tard, ou dix secondes après la fin de la veillée si elle dure davantage (tout de suite si le Conteur arrête la partie), la **carte est remise en état** : chacun est ramené à la salle d'attente, puis les blocs cassés ou posés, les lanternes, le contenu des coffres et les objets tombés au sol retrouvent leur état d'avant la partie, par petits lots (une barre montre l'avancement). Une carte faite à la main revient exactement comme elle était, même si elle n'a jamais été enregistrée. Si le serveur s'arrête entre-temps, la remise en état reprend au redémarrage. Pour s'en passer : Pupitre du Conteur › « Réglages de la nuit… » › « Carte remise en état », ou `/vc reglage remise_en_etat non`.

## 9. Mener la partie : le Conteur

**Conteur automatique** : le jeu fait tout (nuits, informations, ouverture des accusations). Le meneur lance la nuit et clôt la journée.

**Conteur humain** : un opérateur devient Conteur (dalle d'obsidienne ou menu). Il ne joue pas, il mène :

- **Pupitre du Conteur** (menu J › Panneau du Conteur) : tout ce que le Conteur peut faire, rangé par moment de la partie. Au salon : devenir le Conteur, joueurs fictifs, mélanger les places, lancer, figures neutres, **imposer des rôles**, réglages, durées, **Éditer le script**, et un nouveau village. Pendant la partie, cinq onglets :

  | Onglet | Ce qu'on y trouve |
  | --- | --- |
  | Partie | Rôles affichés au-dessus des joueurs, accueillir un voyageur, réglages de la nuit, durées et minuteurs, ordre de réveil, journal, note de partie, pause, **Mettre en suspens** |
  | Nuit | Distribution des rôles et convocations (avant la première nuit), faire tomber la nuit, **nuit pas à pas**, étape précédente, passer l'étape, pause |
  | Jour | Rassembler, discussion minutée, **prolonger d'une minute**, ouvrir le Conseil ou clore les accusations, **annuler le vote**, **gracier** le joueur sur le billot, clore la journée, **ma voix à tous** |
  | Parole | Donner la parole au suivant, tour de table, temps par orateur, arrêter |
  | Fin | Journal, **victoire du bien**, **victoire du mal**, arrêter sans révéler (un second clic confirme) |

- **Nuit pas à pas** : l'ordre complet de la nuit, avec l'étape en cours ; la carte de l'étape (qui se réveille, ce qu'il faut faire et dire, les mises en garde : ivre, empoisonné, mort, déconnecté) ; et les gestes à la main : réveiller ou rendormir un joueur, lui demander de désigner quelqu'un, lui montrer une information libre, poser un jeton de rappel.
- **Jetons de rappel** : chaque rôle en jeu a les siens (« protégé », « faux présage », « a tiré »…), plus des jetons communs. Un clic sur la fiche d'un joueur pose ou retire un jeton ; « Autre rappel » en écrit un libre.
- **Reprendre la main** : la **pause** gèle les minuteurs, la nuit et le vote ; un vote annulé rend à l'accusateur et à l'accusé le droit d'accuser et d'être accusé ; « étape précédente » rejoue un réveil (ses effets déjà appliqués ne sont pas annulés). Pour une interruption plus longue, la **mise en suspens** envoie tout le monde au hub (partie 6).
- **Joueur déconnecté** : le Conteur est prévenu et peut le **faire relayer** par un joueur fictif jusqu'à son retour. À son retour, le joueur retrouve sa place, son rôle et ce qu'on attendait de lui. En partie automatique, un joueur parti pendant son choix de nuit a trente secondes pour revenir.
- **Temps de parole** : un joueur demande la parole (menu J, ou `/vc parole`) ; le Conteur la donne, ou lance un tour de table. Une barre montre le temps de l'orateur, une cloche sonne à la fin. En partie automatique, la file avance toute seule.
- **Grimoire** (lutrin au centre de la place, ou panneau) : le vrai rôle de chacun, qui est empoisonné, protégé, le faux présage ; sur chaque siège : tuer, ressusciter, empoisonner, changer le rôle, poser une note ou un **rappel**.
- **Au-dessus de chaque joueur**, le Conteur voit son nom et son rôle, en bleu (bien) ou rouge (mal), à travers les murs (désactivable dans son panneau).
- **Bâton du Conteur** : viser un joueur affiche son rôle, son état et ses rappels ; clic droit : sa ligne du grimoire ; accroupi + clic droit : un **aparté** (vocal seul à seul) ; avant la première nuit, ce même geste **convoque** le joueur.
- **Salle de convocation** : avec un Conteur humain, les rôles ne tombent plus tous d'un coup au lancement. Le Conteur reçoit chaque joueur dans une salle à l'écart : « Convoquer le suivant » (onglet Nuit du pupitre, ou `/vc convoquer suivant`) y amène le Conteur et le joueur suivant du cercle, face à face ; le joueur découvre son rôle, le Conteur le lui explique, et personne d'autre ne les entend. Le bouton suivant le renvoie sur son trône et amène le prochain ; « Renvoyer … au village » (`/vc convoquer fin`) termine. Faire tomber la nuit remet d'un coup les rôles restants. On peut aussi convoquer n'importe qui en plein jour (`/vc convoquer <joueur>`, ou le bouton de sa fiche). Réglage « Rôles remis à l'écart » pour revenir à la distribution immédiate. Un village généré a sa salle ; sur une carte faite à la main, posez-la avec l'outil « salle de convocation » (sans salle, l'entretien se fait sur place, en vocal privé).
- **Les informations de nuit** : le Conteur relit chaque information avant l'envoi et peut la modifier (rôles ivres, empoisonnés, faux-semblants). Quand une règle laisse le choix au Conteur (Mouchard, Excommunié, Châtelain), la nuit se met en pause et lui pose la question.
- **Bandeau de nuit** : qui est réveillé, et ce qu'on attend. Les joueurs déconnectés sont signalés.
- **Tablette** : `/vc tablette` donne un lien pour mener la partie depuis un téléphone ou une tablette. On y retrouve la nuit pas à pas, les jetons de rappel, la pause, l'annulation du vote, le journal filtré par nuit et par jour, et la modification d'une information avant son envoi.
- **La partie est enregistrée** à chaque moment clé, chaque minute et à l'arrêt du serveur, dans sa propre fiche (`config/valcombe/parties/<monde>.json`) : elle reprend toute seule au redémarrage, avec ses figures, son scénario, ses rappels et ses notes.

## 10. Chaque rôle, et comment il se joue

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

Les rôles de la table (la Sorcière, la Fleuriste, le Jongleur, le Bavard, et les noms de notre feuille) sont décrits dans le [guide du joueur](GUIDE-JOUEUR.md). Les **rôles créés dans l'Atelier** se jouent de la même façon : leur texte est sur la carte de rôle et dans les fiches (menu J › Fiches des rôles).

## 11. L'Atelier : cartes, structures, parties, rôles et scénarios

L'Atelier sert à préparer le jeu : la bibliothèque de cartes, le catalogue de structures, les parties du serveur, les rôles et les scénarios. Il est réservé aux opérateurs. Il existe en deux versions qui suivent les mêmes parcours et lancent les mêmes opérations (ce qui est fait d'un côté apparaît de l'autre) :

- **L'Atelier en jeu** : menu **J** › **Atelier**, ou `/vc atelier jeu`. Tout ce qui touche aux cartes et aux structures s'y fait, sans navigateur.
- **L'Atelier web** : une page à ouvrir dans un navigateur, où s'écrivent les rôles et les scénarios. Depuis le launcher : page **L'Atelier** › **Ouvrir l'Atelier** (le launcher démarre le serveur de votre PC si besoin). Sur un serveur en ligne : `/vc atelier` donne un lien, à ouvrir sur un ordinateur du même réseau (ou par tunnel SSH sur un serveur loué, voir [HEBERGEMENT.md](HEBERGEMENT.md)). Chaque opérateur a son propre lien : on prépare à plusieurs, en même temps.

Les blocs, eux, se bâtissent dans le jeu, dans les **ateliers** : l'atelier carte (partie 12) et l'atelier structures (partie 13), avec WorldEdit (partie 14).

### L'accueil de l'Atelier en jeu

L'écran « Atelier de Valcombe » s'ouvre sur **quatre grandes entrées**, chacune avec ce qu'elle contient en ce moment (« 12 cartes · 2 en atelier », « 2 parties · 1 en cours ») : **Cartes**, **Structures**, **Parties**, **Rôles et scénarios**. En bas, « Page web » donne le lien de l'Atelier web dans le chat, « Fermer » referme l'écran.

Sur chaque page : en haut, le fil d'Ariane (« Atelier › Cartes › Port ») mène d'un clic aux pages d'avant ; en bas, « Retour », et la réponse du serveur à la dernière action (une coche verte quand c'est fait, une croix rouge et la raison quand c'est refusé) ; en haut à droite, l'avancement d'une carte en train d'être posée. Ce qui détruit ou remplace quelque chose se confirme sur place : la fiche dit ce qui va se passer, en rouge, avec « Annuler » à côté. Le bouton de l'action suivante a un halo doré ; un bouton grisé dit pourquoi au survol. L'écran tient au clavier (Tab, flèches, Entrée, Échap) et à la molette.

Un joueur qui n'est pas opérateur voit à la place un mot qui le renvoie au menu « Mondes », où se trouvent les ateliers où il est invité.

### Cartes

Trois volets : **Bibliothèque**, **Ateliers ouverts**, **Corbeille**.

1. **La bibliothèque en vignettes** : chaque carte vue de dessus (faite par le serveur, avec ses trônes dorés, ses maisons vertes, sa salle violette), ses repères, « Jouable » ou ce qui lui manque, sa date, « En atelier » si un atelier carte l'a ouverte. Une recherche (sans souci des accents) et un tri (nom, plus récentes, trônes).
2. **La fiche d'une carte** (un clic) : la grande vignette, sa taille, ses repères, ses versions, ce qui lui manque, et les actions :
   - **Ouvrir dans un atelier** (ou « Aller à son atelier » si elle y est déjà) ;
   - **Envoyer à la partie…** : on choisit la partie (sa carte actuelle en vignette), puis « Confirmer l'envoi » ;
   - **Dupliquer** (la copie est mise en avant dans la bibliothèque), **Renommer…** ;
   - **Exporter** : un fichier `.valcombe` dans `config/valcombe/creations/partages` ;
   - **Supprimer** : à la **corbeille**, après confirmation. La carte y reste 30 jours avec ses versions ; « Restaurer » (volet Corbeille) la remet. Une carte ouverte dans un atelier ne se supprime pas : fermez d'abord son atelier.
3. **Nouvelle carte**, en trois étapes, une à la fois :
   - **Départ** : village généré, carte vierge, ou fichier `.schem`, `.litematic`, `.nbt` déposé dans `config/valcombe/creations/import` ;
   - **Réglages** : le nom ; pour un village, le thème, les trônes, la taille et les maisons, avec l'aperçu du plan ;
   - **Vérifier** : où elle naît, **dans un atelier** (« Créer dans un atelier », « Générer dans un atelier », « Importer dans un atelier ») ou **dans une partie** (« Générer dans la partie », « Envoyer une carte vierge » : son monde est remis à neuf).

   Ce qui manque pour passer à l'étape suivante est écrit en rouge, et le bouton à presser est mis en avant d'un halo doré.
4. **Ateliers ouverts** : chaque atelier carte, avec « Y aller », « Enregistrer » et « Fermer l'atelier » (confirmé).

### Structures

Deux volets : **Catalogue** et **Construire**.

- **Le catalogue en vignettes** : chaque structure vue de trois quarts (peinte par le serveur, aux couleurs de ses blocs), rangée par rayon (**Tout**, **Maisons**, **Place**, **Décor**, **À vous**), avec son état : *Fournie*, *Modifiée* (retouchée dans l'atelier structures) ou *Créée*. Une recherche sans souci des accents.
- **La fiche d'une structure** : la grande vignette, sa taille, ses blocs, son repère (maison, salle), et les actions **Prendre en main** (son **fantôme** suit votre regard, clic droit pour la poser ; dans une partie, le mode construction s'ouvre d'abord), **Voir en 3D** (la maquette qui tourne, « Tourner à gauche » et « Tourner à droite »), **Aller sur sa parcelle** (dans l'atelier structures, devant son pupitre, d'où que l'on soit), et selon le cas **Revenir à l'originale** (une structure fournie modifiée, confirmé) ou **Renommer…** et **Supprimer** (une structure à vous, confirmé : ses fichiers vont à la corbeille du serveur).
- **Une structure en main** a sa barre en bas du catalogue : **Tourner**, **Retourner**, **À mes pieds**, **Ranger**.
- **Nouvelle structure**, en trois étapes : le départ (**Depuis la sélection** WorldEdit, ou une **Parcelle vide** de l'atelier structures), les réglages (le nom ; le rayon, ou la taille de la parcelle), la vérification. Sans sélection, ou hors d'un atelier, la case le dit en rouge et « Suivant » reste grisé.
- **Le volet Construire** reprend les gestes de WorldEdit (remplir, remplacer, vider, murs, étendre, copier, couper, coller, coller sans l'air, tourner et retourner la copie, déplacer, empiler, désélectionner, « En structure… », annuler et rétablir, la baguette) avec la taille de la sélection. Hors des ateliers, il mène à l'atelier structures ou au menu « Mondes ».

### Parties

- **Les parties du serveur en vignettes** : la carte que chacune porte, vue de dessus, avec le ciel de sa phase, son état, ses joueurs, son scénario. **Créer une partie d'essai** (le nom s'écrit sur place ; la nouvelle partie est mise en avant) ; **Rejoindre ou regarder…** mène à l'écran Parties du menu J.
- **La fiche d'une partie** : **Y aller**, **Envoyer une carte…** (une carte de la bibliothèque en vignette, puis « Confirmer l'envoi »), **Suspendre** ou **Reprendre** une partie lancée, **Supprimer la partie** (une partie d'essai, confirmé).
- **Carte en place…** : **Enregistrer** ses retouches dans la bibliothèque, **Enregistrer sous…** (un nouveau nom), **Carte vide…** et **Vider le monde…** (confirmés).
- **Retoucher sur place…** (dans son monde, hors partie) : le mode construction et les repères à la main (partie 15).

### Rôles et scénarios

Trois volets :

- **Scénarios** : chacun avec ses rôles en jetons (en éventail), ses nombres de joueurs, sa carte et les parties où il est choisi. Sa fiche montre tous ses jetons, sa description, sa carte en vignette, et les actions **Choisir pour une partie…** (la partie en vignette ; s'il demande une autre carte, c'est dit avant de confirmer ; « Ne plus le choisir » là où il l'est déjà), **Partie d'essai…** (le nombre de joueurs, puis « Lancer l'essai » : vous et des bots), **Page web**, **Supprimer** (confirmé).
- **Rôles créés** : chacun son jeton (emblème et camp) ; sa fiche : son pouvoir, ses réveils, les scénarios qui le citent, **Modifier sur la page web**, **Supprimer** (confirmé).
- **Packs** : les fichiers `.valcombe` déposés dans `config/valcombe/creations/import` (ou `partages`) ; **Importer** en fait des rôles, des scénarios, des cartes et des structures du serveur.

Écrire un rôle ou un scénario se fait sur la page web (bouton « Page web », le lien arrive dans le chat).

### La page web

En haut : le menu **Partie visée** (la partie qui reçoit les scénarios choisis et les parties d'essai lancées d'ici) et **Activité** (qui est sur la page, les dernières modifications). Quatre onglets, comme dans le jeu, lisibles sur une tablette comme sur un ordinateur :

- **Cartes** : la bibliothèque avec la vignette de chaque carte, ses trônes et maisons, ce qui lui manque pour qu'on y joue, et des filtres (Toutes, En atelier, Villages générés, Faites à la main). La fiche d'une carte l'**envoie à une partie** (chaque partie avec son état : une partie qui se joue dans la Partie principale n'empêche pas d'envoyer une carte à une partie d'essai), l'**ouvre dans un atelier** (ou « Y aller dans le jeu » si son atelier est déjà ouvert), la **duplique**, la **renomme** ou la **supprime**. « Nouvelle carte » ouvre un atelier sur une carte vierge ou un village généré. La page n'édite pas de blocs.
- **Structures** : le catalogue par rayon (maisons et salles, pour la place, décor, vos structures), avec une vignette par structure. « Voir dans l'atelier » vous conduit devant sa parcelle ; « Dupliquer » en fait une copie à vous ; vos structures se renomment, changent de repère ou se suppriment ; une structure fournie modifiée peut « Revenir à l'originale ». « Importer un fichier » ajoute un `.schem`, `.litematic` ou `.nbt` au catalogue ; « Aller à l'atelier des structures » vous y conduit dans le jeu.
- **Parties** : chaque partie du serveur, son état, sa carte, son scénario et son Conteur. On y envoie une carte, un village généré ou une construction importée, on vide son monde, on la met en suspens ou on la reprend, on la vise (« Viser cette partie »), et l'on crée une partie d'essai.
- **Rôles et scénarios**, en trois sous-onglets :
  - **Rôles** : partir d'un exemple ou **d'un rôle intégré** (pour en faire une variante), choisir le camp, l'emblème, le pouvoir (23 modèles), le moment du réveil (par rapport à un rôle intégré ou à un autre rôle créé). Un rôle peut porter **jusqu'à trois effets** (par exemple compter les mauvais et protéger), n'agir qu'**une seule fois par partie** (le joueur choisit sa nuit), agir **de jour** (un tir, des représailles quand on l'accuse), et proposer ses propres **jetons de rappel** au Conteur.
  - **Scénarios** : cocher les rôles (ou les ranger en **groupes** : « Ranger la sélection dans un groupe… »), la carte, les réglages, les règles spéciales, les figures neutres, un texte d'ouverture. On peut aussi fixer des **rôles toujours en jeu**, la **répartition des camps** par nombre de joueurs, et l'**ordre de la nuit**. Règles spéciales : pas d'accusation le premier jour, annonces interdites, conversations privées interdites, chuchotements interdits, morts sans vote, rôle révélé à la mort, nombre d'accusations par jour. **Simuler 200 parties** (avec les règles du scénario : la simulation montre aussi le poids de chaque rôle), lancer une **partie d'essai**, **choisir pour la prochaine partie** (de la partie visée).
  - **Partage** : un fichier `.valcombe` à envoyer à un autre hôte. Il emporte rôles, scénarios, cartes et groupes de rôles ; vos structures du catalogue y voyagent aussi, avec les mêmes contrôles de taille à l'arrivée. À l'import, un groupe remplace celui de même nom.

### Bon à savoir

- **Scénario lié à une carte** : le choisir envoie sa carte à la partie, à la place de la sienne (la page et le jeu le demandent d'abord), sauf si c'est déjà elle, intacte, qui est en place. Une **partie d'essai** dont la carte se pose démarre d'elle-même à la fin du chantier (`/vc atelier essai <scénario> [joueurs]`).
- **Liens de l'Atelier web** : chaque `/vc atelier` donne un nouveau lien (l'ancien ne marche plus) ; un lien cesse de fonctionner au bout de douze heures, ou dès que son propriétaire n'est plus opérateur. `/vc atelier fermer` ferme le vôtre. `/vc tablette fermer` ne ferme que la tablette du Conteur : l'Atelier des opérateurs reste ouvert.
- **Groupes de rôles dans le jeu** : l'éditeur de script (Pupitre du Conteur › Éditer le script) propose les groupes dans sa colonne de droite. Cliquer sur le nom passe au groupe suivant (ses jetons sont soulignés d'or), puis « + » l'ajoute au script, « − » l'en retire, « Seul » ne garde que lui ; « Ranger… » enregistre le script en cours comme groupe. En commande : `/vc script groupe <ajouter|retirer|seulement|ranger|supprimer> <nom>`.
- **Fichiers importés** : une construction ou une carte venue d'un fichier est relue bloc par bloc. Rien n'est posé hors de ses dimensions, ses entités sont ignorées, et les blocs de commande, générateurs et commandes au clic des pancartes arrivent vides.

### Envoyer à la partie

C'est le seul chemin pour changer la carte d'un monde de partie : `/vc carte envoyer` (ancien nom : `charger`), `construire`, `generer`, `importer`, `nouvelle`, `vider`, l'Atelier web, l'Atelier en jeu, le Pupitre du Conteur et l'atelier carte y passent tous. Dans l'ordre :

1. **Refus en clair** si une partie s'y joue (on change de carte entre deux parties), ou si un chantier ou une autre carte y est en cours.
2. Si la carte actuelle a été **retouchée à la main** (mode construction, repères, blocs posés ou cassés) sans être enregistrée depuis, elle est d'abord **sauvegardée dans la bibliothèque**, toute la zone bâtie autour de la place et sur toute sa hauteur, sous le nom « Sauvegarde du … ». Si c'est impossible, rien ne bouge. Seules les cinq dernières sauvegardes automatiques sont gardées : renommez celle que vous voulez conserver, elle devient une carte comme les autres.
3. Les joueurs présents sont envoyés **au hub**.
4. Le monde est **remis à neuf** : ses fichiers sont effacés, il revient plat et vide.
5. La carte est posée au centre **par petits lots** : le serveur reste fluide, une barre suit l'avancement pour ceux qui attendent au hub, et la partie ne peut pas commencer avant la fin. Ses trônes, maisons, centre et salle de convocation deviennent le village de la partie.
6. Un message annonce la fin, avec un lien **[Y aller]** pour revenir.

Chaque thème de village a son monument et son décor : puits couvert et étals (gothique), jardin de cloître, carrés de simples et mur d'enceinte (monastère), fanal, cargaisons et chenal (port). `/vc carte nouvelle [monde]` donne un monde neuf avec une carte vide au centre ; `/vc carte vider [monde]` le laisse plat et vide, et l'on joue sans carte, autour du Conteur (le serveur n'y rebâtit rien au démarrage). `/vc carte poser <carte> <monde>` sert pour les autres mondes (ateliers) : dans un monde de partie, il renvoie à `envoyer`.

## 12. L'atelier carte : préparer une carte

Chaque carte en travail a son propre monde, l'**atelier carte** : plat, paisible, toujours à midi. On y bâtit avec WorldEdit, on y pose les repères, et la carte s'y enregistre toute seule dans la bibliothèque. **Un seul atelier par carte** : rouvrir une carte déjà en atelier vous y mène.

1. **Ouvrir** (opérateurs) :
   - Atelier en jeu › Cartes › fiche de la carte › **Ouvrir dans un atelier**, ou **Nouvelle carte** (village généré, carte vierge ou fichier importé, « dans un atelier ») ;
   - menu J › **Mondes** › **Ouvrir une carte…**, en trois onglets : **Bibliothèque** (la carte est posée au centre d'un monde neuf, puis vous y êtes conduit), **Carte vierge** (un nom, et un monde plat et vide), **Village généré** (trônes, taille, thème, maisons ; le village de Valcombe est bâti au centre, salle de convocation et salle d'attente comprises) ;
   - l'Atelier web (« Ouvrir dans un atelier », « Nouvelle carte ») ;
   - `/vc atelier carte ouvrir <carte>`, `nouvelle <nom>`, `village <trônes> [<taille> <thème> <oui|non> <nom>]`, `importer <fichier> [nom]`.
2. **Bâtir** : à l'entrée, le mode créatif, la baguette de construction (WorldEdit) et les sept outils « Carte : … » (trône, maison, centre, salle de convocation, gomme, structure, main).
   - Clic droit sur un bloc : poser le repère ; clic droit sur un repère déjà posé : le prendre, puis cliquer là où le reposer (il garde son numéro) ; la gomme le retire.
   - Accroupi avec l'outil maison ou salle : la construction est bâtie d'un coup (la gomme la retire avec son repère).
   - L'outil structure pose les structures du catalogue (une maison pose son repère de maison avec elle, la salle de convocation le sien).
   - **La main** : clic droit sur un trône, une maison, la salle, le centre ou une structure posée depuis le catalogue ouvre un menu : déplacer, tourner (un trône par huitièmes de tour, ou vers le centre ; une structure d'un quart de tour), retourner, retirer. Une structure retirée rend le terrain d'avant.
   - `/vc annuler` défait aussi les repères de l'atelier. Les invités de l'atelier y bâtissent et y posent les repères comme les opérateurs.
3. **Voir où l'on en est** : chaque repère porte une étiquette flottante, visible à travers les murs (« ♛ Trône 3 », « ⌂ Maison 3 », « ✉ Salle de convocation », « ✚ Centre de la place »), et un contour au sol ; un trait de lumière relie le trône et la maison du même numéro quand on s'en approche (le joueur du trône 3 dort dans la maison 3) ; le bord de la zone de la carte est tracé autour de vous. Au-dessus de la place, un panneau compte les repères (« 12 trônes sur 16, 12 maisons, salle de convocation : oui »), dit si la carte est **jouable** (5 trônes au moins) ou ce qui lui manque, et si elle a changé depuis son dernier enregistrement. Le même compte s'affiche au-dessus de la barre d'objets.
4. **L'écran de l'atelier** : menu J › Mondes › **Atelier de la carte** (ou `/vc atelier carte`, ou le lien du message d'accueil). À gauche, la fiche : le compte des repères, ce qui manque, les conseils (trônes sans maison, pas de salle de convocation), le dernier enregistrement. À droite :
   - **Enregistrer** : la carte va dans la bibliothèque (toute la zone bâtie autour de la place, repères compris) et une **version** est gardée. Si rien n'a changé, rien n'est réécrit.
   - **Enregistrer sous…** : une copie sous un autre nom ; l'atelier continue sur la copie, l'originale reste telle qu'enregistrée.
   - **Versions (n)…** : les enregistrements gardés ; « Revenir à cette version » la remet dans la bibliothèque et dans l'atelier. L'état présent est d'abord gardé, lui aussi : un retour en arrière se défait.
   - **Envoyer à la partie…** : choisissez le monde de partie (la Partie principale ou une partie d'essai) ; la carte est enregistrée, puis envoyée (son monde est remis à neuf). L'atelier reste ouvert. Une carte qui n'est pas jouable n'est pas envoyée.
   - **Fermer l'atelier…** (avec confirmation) : la carte est enregistrée, puis le monde de l'atelier est supprimé ; ceux qui s'y trouvent vont au hub. Une carte vierge où rien n'a été fait n'est pas rangée.

   Ouvrir, enregistrer, envoyer et fermer sont l'affaire des opérateurs.
5. **Enregistrement automatique** : toutes les 5 minutes s'il y a eu du changement, à l'arrêt du serveur et à la fermeture de l'atelier. Chaque carte garde ses 10 dernières versions automatiques et ses 20 dernières versions demandées (enregistrer, fermer, envoyer) : une rafale d'enregistrements automatiques n'emporte jamais une version demandée. Elles sont rangées dans `config/valcombe/creations/cartes/versions/<carte>/`. Une pose coupée par un arrêt du serveur reprend au démarrage.
6. **Commandes** (dans l'atelier) : `/vc atelier carte enregistrer`, `enregistrer-sous <nom>`, `versions`, `revenir <version>`, `envoyer [monde]`, `fermer [confirmer]`, `infos`, `outils` ; de partout, `liste` et `aller <carte>`. Dans un atelier, `/vc carte trone`, `maison`, `centre`, `retirer trone|maison`, `annuler`, `ordonner` (renumérote trônes et maisons dans le sens des aiguilles d'une montre) et `infos` visent la carte de l'atelier. Un repère ne se pose pas à plus de 192 blocs de la place.

Le fonctionnement interne (fichiers du monde, vues, zone cherchée) est décrit dans [docs/ATELIER-CARTE.md](docs/ATELIER-CARTE.md).

## 13. L'atelier structures

Un seul monde où **toutes les structures** du catalogue, fournies avec le mod et créées par vous, sont posées chacune sur sa parcelle. On les y modifie sur place, et ce qu'on enregistre est gardé.

1. **Y aller** : menu J › Mondes › « Atelier structures » › « Y aller » (il est créé à la première visite) ; ou Atelier en jeu › Structures › fiche › **Aller sur sa parcelle** ; ou l'Atelier web (« Aller à l'atelier des structures », « Voir dans l'atelier ») ; ou `/vc structures atelier`. À la première visite, on arrive en vol au-dessus de l'accueil, toutes les parcelles en vue.
2. **Ce qu'on y trouve** : une parcelle par structure (sol quadrillé, une ligne blanche tous les 4 blocs depuis l'origine, bordure d'ardoise), des rangées par catégorie (maisons et salles, pour la place, décor, vos structures), et dans l'allée au nord de chaque rangée le **pupitre** de chaque parcelle (un lutrin) avec un panneau flottant : nom, rangée, état (« fournie », « modifiée », « créée », « nouvelle »). À l'accueil, un pupitre ouvre la liste de toutes les parcelles.
3. **Modifier une structure** : bâtissez sur sa parcelle, à la main ou avec WorldEdit, puis clic droit sur son pupitre › **Enregistrer**. L'intérieur de la bordure est capturé (le quadrillage du sol ne compte pas ; un trou creusé dedans, si) ; l'origine reste l'ancre de la structure.
4. **Une structure fournie modifiée remplace l'originale partout** : dans les villages générés (maisons, salle de convocation, pavillon, monument du centre, étals, arbres, carrés de simples, tonneaux, lampadaires), l'éditeur de carte, les Ateliers et les cartes qui la posent ensuite. **Revenir à l'originale** (au pupitre, dans la fiche de l'Atelier en jeu ou sur la page web, confirmé) retire la modification (elle va à la corbeille) et repose l'originale.
5. **Les autres actions du pupitre** : **Reposer** (abandonne les changements en cours), **Tourner** (un quart de tour autour de l'origine), **Renommer** et **Repère** (vos structures : aucun, maison, salle de convocation), **Supprimer** (vos structures, après confirmation : elles vont à la corbeille du catalogue), **Nouvelle structure** (une parcelle vide, nommée : petite 11, moyenne 17, grande 25 ou immense 41 blocs de côté, au bout de la rangée « Vos structures »).
6. L'écran dit si la parcelle a des **modifications non enregistrées**. Si la structure change ailleurs pendant ce temps, rien n'est écrasé : la parcelle est marquée « changée ailleurs ».

Commandes : `/vc structures liste | aller <id> | choisir <id>`, `enregistrer [id] | originale [id] | reposer [id] | tourner [id]`, `renommer <nom> | repere <maison|salle|aucun> | supprimer [confirmer]`, `nouvelle <petite|moyenne|grande|immense> <nom>`. Sans identifiant, une action vise la parcelle où l'on se tient. Le détail est dans [docs/ATELIER-STRUCTURES.md](docs/ATELIER-STRUCTURES.md).

## 14. Construire : WorldEdit dans les ateliers

On construit avec **WorldEdit**, livré avec le modpack et le serveur, **dans les ateliers seulement** (atelier carte, atelier structures). Au hub et dans les mondes de partie (Partie principale comme parties d'essai), personne ne s'en sert, opérateurs compris : une commande WorldEdit tapée là-bas reçoit une phrase claire qui dit où aller. On y casse et pose toujours à la main comme avant : l'opérateur en mode créatif, personne d'autre. Poser suit exactement la même règle que casser : un opérateur en survie n'y pose ni bloc ni seau.

- **Qui** : les opérateurs, et les joueurs qu'ils **invitent**. `/vc atelier inviter <joueur> [atelier|tous]` (sans atelier : celui où vous êtes), `/vc atelier retirer <joueur> [atelier|tous]` (sans atelier : toutes ses invitations), `/vc atelier liste`. Un invité voit l'atelier dans menu J › **Mondes** (« invité à construire ») et y va depuis le hub ; un opérateur y voit qui est invité. Les invitations sont gardées dans `config/valcombe/ateliers.json` et visibles dans l'API d'administration. Un invité n'a jamais ce qui touche au serveur : recharger WorldEdit, changer de monde, scripts, instantanés, effacer ou écrire des fichiers de schémas, défaire le travail d'un autre joueur. Il laisse son inventaire à l'entrée de l'atelier et le retrouve en sortant (même après un redémarrage du serveur) : ce qu'il prend en créatif reste dans l'atelier. Son invitation retirée, il revient au hub.
- **En arrivant** : le mode créatif, et la **baguette de construction** (baguette de WorldEdit dessinée pour Valcombe). En repartant, chacun retrouve son mode d'avant, et la baguette est rendue. `/vc baguette` en redonne une ; celle de `//wand` prend son apparence.
- **La sélection** : clic gauche avec la baguette, premier coin (rouge) ; clic droit, second coin (bleu). Elle s'affiche en 3D : arêtes dorées, quadrillage léger, les trois côtés mesurés (L est-ouest, l nord-sud, h hauteur) et au-dessus « L 12 × l 8 × h 5 » avec le nombre de blocs, lisibles de loin et à travers les murs. Sans la baguette : `//pos1` et `//pos2` (là où vous êtes), `//hpos1` et `//hpos2` (le bloc visé), `//expand <n> [direction]`, `//contract`, `//shift`, `//sel` pour effacer. D'autres formes : `//sel poly`, `//sel ellipsoid`, `//sel cyl`.
- **Les commandes utiles** (elles s'écrivent avec deux barres ; le volet Construire de l'Atelier en jeu propose les plus courantes en boutons) :

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

- **Annuler** : `/vc annuler` défait **dans l'ordre** vos modifications WorldEdit et les gestes de Valcombe (poses de structures du catalogue, repères), jusqu'à 40 niveaux, contenu des coffres compris. WorldEdit garde son historique, Valcombe le sien, et Valcombe tient l'ordre des gestes. `//undo`, `/vc carte annuler` et le bouton « Annuler » de l'Atelier marchent toujours : l'ordre suit. Une modification des repères annulée ne se rétablit pas : reposez le repère.
- **En faire une structure** : Atelier en jeu › Structures › « Nouvelle structure » › « Depuis la sélection » (aussi « En structure… » du volet Construire), ou `/vc structures depuis-selection <id> "<nom>" [catégorie]` (ou `/vc structure enregistrer <id> <nom>`). Tenez-vous dans la sélection, à l'endroit qui devra se trouver sur le bloc visé (sinon ce sera le milieu de sa base). La structure rejoint « Vos structures », ou le rayon donné en catégorie (`maisons`, `place`, `decor`), et se pose ensuite avec l'outil de structure. Une sélection d'une autre forme que le pavé (polygone, ellipsoïde, cylindre) ne garde que ses propres cases.
- **Parties d'essai** : WorldEdit y est fermé ; `/vc atelier worldedit-essais oui` l'ouvre aux opérateurs (`non` le referme).

## 15. Retoucher la carte d'une partie

Préparer une carte se fait dans son atelier carte (partie 12). Pour une petite retouche de la carte en place dans un monde de partie, entre deux parties, il y a le **mode construction** (opérateurs, jamais pendant une partie ; sans WorldEdit) :

- **Entrer** : Atelier en jeu › Parties › fiche de la partie › **Retoucher sur place…** › « Entrer en construction » (dans son monde), ou `/vc carte construction` (la même commande en sort). Vous passez en créatif avec les sept outils « Carte : … » ; « Mes outils » (`/vc carte outils`) les redonne.
- **Retoucher sur place…** propose aussi **Prendre la main**, **Trône ici**, **Maison ici**, **Centre ici** (là où vous vous tenez), **Salle d'attente** (la rebâtir, `/vc carte salle`), **Renuméroter** (`/vc carte ordonner`), **Annuler le geste** (`/vc carte annuler`), et la **liste de contrôle** de la carte.
- **Le catalogue de structures** : `/vc structure liste` montre les structures fournies (maisons des trois thèmes, salle de convocation, pavillon, puits, cloître, fanal, fontaine, estrade, arche, étals, arbres, lampadaires, banc…) et les vôtres. `/vc structure poser <id>` en prend une en main (comme « Prendre en main » de l'Atelier) : avec l'outil « Carte : structure », un contour bleu suit le bloc visé ; **clic droit** pour la poser, **accroupi + clic droit** pour la tourner d'un quart de tour, **clic gauche** pour la retourner (`/vc structure tourner`, `miroir`, `ici` font la même chose sans l'outil).
- **Importer un fichier** comme structure (sans toucher à la carte) : déposez-le dans `config/valcombe/creations/import` puis `/vc structure importer <fichier>`. `renommer`, `supprimer` et `recharger` complètent. Fichiers : `config/valcombe/creations/structures`.
- **Garder la retouche** : Atelier › Parties › fiche › Carte en place… › **Enregistrer** (ou « Enregistrer sous… »). Sinon, la prochaine carte envoyée la sauvegarde d'office (« Sauvegarde du … »), et la remise en état d'après partie la garde telle quelle.

Annuler, rétablir : `/vc annuler [fois]` et `/vc retablir [fois]` reviennent sur les poses de structures et les modifications des repères, dans l'ordre, jusqu'à 40 niveaux. L'historique des structures est remis à zéro quand on change de carte ou qu'une partie commence.

## 16. Le serveur au quotidien

- **Sauvegardes automatiques** : le mod sauvegarde les mondes (le hub, les parties, les ateliers) et les créations de l'Atelier dans `backups/` : toutes les 6 heures et à chaque arrêt, en gardant les 10 dernières ; celle qui tombe pendant une partie attend la fin de la partie (24 heures au plus ; une partie en suspens ne la retient pas). Le jeu continue pendant la copie.
- **Dans le launcher**, page Serveur › onglet **Sauvegardes** : la liste des archives (date, sorte, taille, mondes), **Sauvegarder maintenant**, le téléchargement d'une archive sur votre PC (flèche ; elle est contrôlée à l'arrivée), **restaurer** (une confirmation dit ce qui sera perdu, ce qui est gardé à part et ce qui ne change pas ; « Restaurer et redémarrer » ou « Au prochain démarrage seulement » ; après coup, « Revenir à l'état d'avant »), l'effacement, et les réglages (fréquence, nombre gardé, à l'arrêt, pendant une partie).
- **En jeu ou à la console** : `/vc sauvegarde` (où elles en sont), `/vc sauvegarde maintenant`, `/vc sauvegarde liste`, `/vc sauvegarde restaurer <nom>` (puis redémarrer), `/vc sauvegarde reglages`.
- **Les parties en cours** sont enregistrées à l'arrêt et reprennent au démarrage ; avant un redémarrage long, mettez-les en suspens (partie 6).
- **Mettre à jour** : voir la fin de la partie 4.
- **Statistiques** : `/vc stats` (classement), `/vc stats <pseudo>`. **Discord** : `/vc discord <URL>`, `/vc discord essai`, `/vc discord aucun`.

Les détails (contenu d'une archive, rotation, restauration à la main) sont dans [HEBERGEMENT.md](HEBERGEMENT.md) et [docs/ADMIN-API.md](docs/ADMIN-API.md).

## 17. En cas de souci

| Souci | Solution |
| --- | --- |
| Bandeau rouge « autre version du mod » | L'hôte met à jour le mod du serveur et le redémarre (partie 4). |
| « Argument incorrect pour la commande » | Même cause : serveur pas à jour. |
| Personne ne s'entend | Port **24454/UDP** fermé, ou micro mal choisi (touche V). Vérifiez aussi que vous êtes dans le monde de la même partie : le vocal est séparé par partie. |
| « Rejoindre » est grisé | Le survol dit pourquoi : partie déjà commencée, salon plein, partie en suspens, ou vous êtes assis à une autre partie en cours. Une partie commencée se **regarde**. |
| On ne peut pas entrer dans un monde de partie | La partie est en suspens : seuls son Conteur et les opérateurs y entrent. Elle rappellera ses joueurs à sa reprise. |
| Une commande WorldEdit est refusée | WorldEdit ne sert que dans les ateliers (atelier carte, atelier structures), pour les opérateurs et leurs invités (partie 14). |
| « Envoyer à la partie » est refusé | Une partie s'y joue (arrêtez-la ou attendez la fin), ou une carte y est déjà en route. Le message le dit. |
| Message rouge « La fiche du village est illisible » ou « Un chantier a été interrompu » à la connexion d'un opérateur | Le serveur n'a rien rebâti de lui-même, pour ne rien poser sur une carte à moitié faite. Envoyez de nouveau une carte à la partie (`/vc carte envoyer <carte>`) ou un nouveau village (`/vc carte construire`) : le monde est remis à neuf d'abord. |
| « Windows a protégé votre ordinateur » | Informations complémentaires › Exécuter quand même. |
| Le jeu plante | Launcher › Réglages › Rapport de plantage : une archive sur le Bureau, à envoyer à l'hôte. |
| Le launcher n'est pas dans le menu Démarrer | Lancez-le une fois depuis son dossier : il crée ses raccourcis. |

## 18. Aide-mémoire des commandes

Toutes les actions des écrans existent aussi en commandes `/vc` ; la liste des commandes de partie est dans [minecraft/README.md](minecraft/README.md).

| Commande | Qui | Effet |
| --- | --- | --- |
| `/vc hub`, `/vc monde [liste]`, `/vc monde aller <monde>` | Tous | Aller au hub, voir les mondes, y aller |
| `/vc partie [liste]` | Tous | Les parties du serveur |
| `/vc partie rejoindre <partie>`, `regarder <partie>`, `sortir`, `voir <partie>`, `aller <partie>` | Tous | Rejoindre, regarder, quitter les gradins, la fiche d'une partie, aller dans son monde |
| `/vc partie creer <nom>`, `/vc partie supprimer <partie>` | Opérateurs | Créer ou supprimer une partie d'essai |
| `/vc partie suspendre [partie]`, `/vc partie reprendre [partie]` | Son Conteur, opérateurs | Mettre en suspens, reprendre |
| `/vc carte envoyer <carte> [monde]`, `construire [thème]`, `generer …`, `vider [monde]`, `nouvelle [monde]` | Opérateurs | Changer la carte d'une partie (Envoyer à la partie) |
| `/vc atelier`, `/vc atelier jeu`, `/vc atelier fermer` | Opérateurs | Lien de l'Atelier web, Atelier en jeu, fermer son lien |
| `/vc atelier carte …` | Opérateurs | Ouvrir, enregistrer, envoyer, fermer un atelier carte (partie 12) |
| `/vc structures …` | Opérateurs, invités | L'atelier structures (partie 13) |
| `/vc atelier inviter\|retirer <joueur> [atelier\|tous]`, `/vc atelier liste` | Opérateurs | Invitations à construire |
| `/vc annuler [fois]`, `/vc retablir [fois]`, `/vc baguette` | Opérateurs, invités | Annuler et rétablir, baguette de construction |
| `/vc monde creer\|recreer\|renommer\|supprimer …` | Opérateurs | Gérer les mondes à la main |
| `/vc sauvegarde …` | Console, opérateurs de niveau 4 | Sauvegardes (partie 16) |
| `/vc admin`, `/vc admin cle`, `/vc admin nouvellecle` | Console, opérateurs de niveau 4 | API d'administration |
| `/vc tablette`, `/vc soiree <texte>`, `/vc discord <URL>`, `/vc stats` | Selon le cas | Tablette, soirée, Discord, statistiques |
