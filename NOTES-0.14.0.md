## Les mondes de Valcombe

- **Chaque partie a son monde.** La Partie principale se joue dans un monde plat à elle, où le village de Valcombe est bâti au premier démarrage. Le monde principal de Minecraft ne sert plus.
- **Le hub**, une salle d'accueil commune : on y arrive en se connectant. Les dalles (s'inscrire, se désinscrire, devenir Conteur), le **tableau des parties** (une fiche par partie, à cliquer pour la rejoindre ou la regarder), des **portes** vers le village de chaque partie, et le mur des rôles.
- **Menu J › « Mondes »** : aller d'un monde à l'autre. Chacun retrouve sa dernière position dans chaque monde. `/vc hub` ramène au hub.
- Tous les mondes de Valcombe sont plats et paisibles ; dans les ateliers, pas de météo et toujours midi. Plus de bulles de progrès de Minecraft dans ces mondes.

## Plusieurs parties en même temps

- La **Partie principale** et autant de **parties d'essai** qu'on veut, chacune avec ses joueurs, son Conteur, son scénario, ses minuteurs, ses noms au-dessus des têtes et sa sauvegarde. Le **vocal est séparé** : on n'entend jamais une autre table.
- **L'écran « Parties »** (menu J au hub, ou une fiche du tableau) : **Rejoindre** (ou **Y retourner** à sa place), **Regarder** en spectateur, sans voir les rôles ni entendre les joueurs (« Arrêter de regarder » pour revenir au hub). Les opérateurs y créent et suppriment les parties d'essai.
- **Mettre en suspens** : Pupitre du Conteur › onglet Partie › « Mettre en suspens ». L'horloge est gelée, la partie enregistrée, les joueurs vont au hub. À la reprise, un lien **[Y aller]** ramène chacun à son trône, et la partie repart quand tout le monde est revenu.
- **Toutes les parties reprennent après un redémarrage** du serveur, en cours, en pause ou en suspens.
- Commandes : `/vc partie liste|voir|rejoindre|regarder|sortir|aller|creer|supprimer|suspendre|reprendre`.

## Envoyer à la partie

- Changer la carte d'une partie passe toujours par **« Envoyer à la partie »** : le monde de la partie est **recréé à neuf**, plat et vide, puis la carte y est posée par petits lots. Il ne peut plus rien rester de l'ancienne carte, quelle que soit sa taille.
- Une carte retouchée à la main et pas enregistrée est d'abord sauvegardée dans la bibliothèque (« Sauvegarde du … »). Les joueurs présents attendent au hub, une barre suit l'avancement, et un lien **[Y aller]** les ramène à la fin.
- Depuis l'Atelier en jeu (fiche d'une carte ou d'une partie), l'Atelier web, le Pupitre du Conteur (nouveau village) ou `/vc carte envoyer <carte> [monde]` (l'ancien `charger` marche toujours).

## Les ateliers et WorldEdit

- **Atelier carte** : chaque carte en travail a son propre monde. On y bâtit avec WorldEdit et les outils de repères ; la carte s'**enregistre toute seule** (toutes les 5 minutes, à la fermeture, à l'arrêt) avec ses **versions** (« Revenir à cette version »). Écran « Atelier de la carte » : Enregistrer, Enregistrer sous…, Versions (n)…, Envoyer à la partie…, Fermer l'atelier…
- **Atelier structures** : toutes les structures, fournies et les vôtres, chacune sur sa parcelle avec son pupitre. On la modifie sur place, puis « Enregistrer ». **Une structure fournie modifiée remplace l'originale partout** (villages générés compris) ; « Revenir à l'originale » la restaure. « Nouvelle structure » ouvre une parcelle vide.
- **WorldEdit** (7.4.6) est livré avec le modpack, le serveur et le launcher, et **réservé aux ateliers**, pour les opérateurs et les joueurs qu'ils **invitent** (`/vc atelier inviter <joueur> [atelier|tous]`). Un invité laisse son inventaire à l'entrée et le retrouve en sortant.
- Valcombe le complète : la **baguette de construction** dessinée pour le mod, la **sélection affichée en 3D** avec ses dimensions, **« Sélection → structure du catalogue »**, et un **« annuler » commun** (`/vc annuler`) qui défait dans l'ordre les gestes WorldEdit, les poses de structures et les repères.

## L'Atelier refait

- **Dans le jeu** (menu J › Atelier) : un accueil à **quatre entrées**, **Cartes**, **Structures**, **Parties**, **Rôles et scénarios**, avec des **vignettes** faites par le serveur (cartes vues de dessus, structures de trois quarts, parties avec le ciel de leur phase), des fiches, et des parcours en étapes (« Nouvelle carte », « Nouvelle structure »). Une **corbeille** garde les cartes supprimées 30 jours.
- **Sur la page web** : les mêmes quatre onglets, les vignettes, le menu « Partie visée ». Les rôles et les scénarios s'y écrivent toujours ; les blocs se bâtissent dans le jeu.

## La haute résolution

- Tous les écrans de Valcombe, du menu J au grimoire, sont dessinés à la résolution réelle de l'écran, avec une police lisse et des cadres nets, à toutes les tailles d'interface.
- Les textures du mod (jetons, emblèmes, outils, trônes, objets) sont redessinées en 128 px.

## Le serveur, dans le launcher (0.14.0)

- **Page Serveur**, pour le serveur de ce PC comme pour un serveur distant : démarrer, arrêter, redémarrer, et cinq onglets, **Vue d'ensemble** (joueurs, mondes), **Console** en direct, **Réglages** (liste blanche, opérateurs, message d'accueil, prochaine soirée, joueurs au plus, port, mémoire), **Parties** (suspendre, reprendre, créer et supprimer une partie d'essai) et **Sauvegardes**.
- **Serveur loué (VPS)** : « Ajouter un serveur » › « Enregistrer un serveur loué ». Le launcher s'y connecte par SSH (empreinte vérifiée), l'installe, le démarre, le met à jour (« Mettre à jour (vX) ») et le pilote par un tunnel chiffré.
- **Sauvegardes automatiques** des mondes et des créations (toutes les 6 heures et à chaque arrêt, les 10 dernières gardées), téléchargées d'un clic, et **restaurées** d'un clic, l'état d'avant gardé à part (« Revenir à l'état d'avant »). En jeu : `/vc sauvegarde`.
- **API d'administration** dans le mod, protégée par une clé et réservée à la machine du serveur (`/vc admin cle`). Contrat : `docs/ADMIN-API.md`.
- Le guide du joueur du launcher présente le hub et les parties.

## Ce qui disparaît

- **Le rasage « bâti » ou « carré »** avant un nouveau village : le monde de la partie est recréé à neuf à la place (`/vc carte generer` ne prend plus `bati|carre`).
- **La carte unique dans le monde principal** : chaque monde de partie porte sa propre carte.
- **Les anciens outils maison de sélection** (outil « sélection », `/vc edit remplir|remplacer|murs|copier|couper|coller|deplacer|empiler|etendre…`) : WorldEdit les remplace, et le volet « Construire » de l'Atelier en jeu en reprend les gestes.
- **L'ancien écran d'Atelier à onglets** et l'aperçu en cases (`/vc carte apercu`) : l'Atelier à quatre entrées et ses vignettes les remplacent.
- **WorldEdit hors des ateliers** : au hub et dans les mondes de partie, on casse et on pose à la main, en créatif, comme avant ; les petites retouches de la carte d'une partie se font en mode construction.

## Passer à la 0.14

- **Avant de mettre à jour le serveur** :
  - rangez dans la bibliothèque une **carte faite à la main** qui n'y est pas encore (avec la 0.13 : `/vc carte enregistrer <id> <nom>`, ou « Enregistrer la carte actuelle » de l'Atelier web). Après la mise à jour, l'ancien monde principal n'est plus utilisé (il reste sur le disque) : la carte se renvoie à la Partie principale depuis la bibliothèque ;
  - **finissez la partie en cours** : une partie d'avant la 0.14 ne reprend pas (sa sauvegarde est mise de côté, `config/valcombe-partie.json.ancienne`).
- **Au premier démarrage**, le serveur crée le hub et le monde de la Partie principale, y bâtit le village de Valcombe, ouvre l'API d'administration (la clé s'affiche dans la console) et commence ses sauvegardes automatiques dans `backups/`.
- **Repris tels quels** : la bibliothèque de cartes, vos structures, vos rôles, vos scénarios et groupes, les fichiers de partage, les statistiques, le webhook Discord, la liste blanche et les opérateurs. Les anciens fichiers du village du monde principal (`config/valcombe-village.json`, `valcombe-instantane.nbt`, `valcombe-chantier.txt`) sont ignorés et laissés sur le disque.
- **Les dernières positions** des joueurs dans l'ancien monde principal sont oubliées : tout le monde arrive au hub.

## À savoir

- Le serveur doit passer au mod 0.14.0 en même temps que les joueurs. Le serveur de votre PC se met à jour quand le launcher le démarre (arrêtez-le, puis démarrez-le) ; un serveur loué installé par le launcher, avec « Mettre à jour » ; un serveur monté à la main reçoit le nouveau dossier `mods/`, qui contient maintenant **WorldEdit** (Fantasy est inclus dans le mod Valcombe).
- Le launcher se met à jour une fois (0.14.0) : page Serveur, onglets Parties et Sauvegardes, serveur loué.
- Les joueurs de Modrinth App ou de Prism Launcher importent le nouveau fichier `Valcombe-0.14.0.mrpack` (WorldEdit en plus).
- Les ports ne changent pas : 25565/TCP (jeu), 24454/UDP (vocal), 25570/TCP (tablette, Atelier web et API, à ne pas ouvrir au public).
- Le pas à pas complet est dans le [tutoriel](TUTORIEL.md) ; le guide du joueur et le guide d'hébergement sont à jour.
