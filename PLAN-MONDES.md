# Plan : les mondes de Valcombe, des ateliers pro, un vrai serveur

Plan à valider avant de lancer les sprints 10 à 17. Il reprend vos réponses du 13 nov. 2026.

## Ce que vous avez demandé

| Sujet | Votre choix |
| --- | --- |
| Mondes | Des dimensions dans le même serveur, autant qu'on veut : partie, essai, atelier carte, atelier structures |
| Terrain de la partie | Monde plat infini recréé à neuf, puis la carte (ou le village) posée dessus |
| Carte → partie | « Envoyer à la partie » : l'atelier garde son brouillon |
| Structures fournies | Les modifier les remplace partout ; « Revenir à l'originale » |
| Parties | Plusieurs parties séparées en même temps, chacune avec ses joueurs, son Conteur et son vocal ; mise en suspens possible ; toutes reprennent après un redémarrage |
| Accueil | Un hub commun où l'on choisit sa partie |
| Construction | WorldEdit, à la place des outils maison ; on le complète là où il manque quelque chose |
| Atelier | Passe complète : écrans clairs, outils puissants, plus de bugs de carte |
| Haute résolution | Écrans dessinés à la résolution réelle (police lisse, cadres nets) et textures du mod en 128 px |
| Serveur | Panneau dans le launcher, sauvegardes, réglages, serveur distant par SSH et par l'API du mod ; l'hébergement sur son PC reste possible |
| Accès aux ateliers | Opérateurs, plus les joueurs qu'ils invitent |
| Existant | Nouveaux mondes vides ; la bibliothèque de cartes et vos structures sont reprises et modifiables |
| Méthode | Sprints, agents orchestrateurs et exécutants en parallèle, suivi de bout en bout dans Cap |

## Ce que j'ai vérifié

- **WorldEdit 7.4.6** existe pour Fabric 26.3 (publié le 15 oct. 2026).
- **Fantasy 0.8.4+26.3** existe : c'est la bibliothèque de référence pour créer, ouvrir, enregistrer et supprimer des mondes pendant que le serveur tourne. Elle est incluse dans le mod (rien à installer en plus).
- Les cartes de la bibliothèque sont déjà une structure de blocs et une fiche de repères : elles passent telles quelles dans les nouveaux ateliers.
- Aujourd'hui, tout suppose un seul monde (37 appels au monde principal) et une seule partie (31 appels à la session unique). Ce sont les deux gros chantiers.

## L'architecture

### Les mondes

- Un **gestionnaire de mondes** tient la liste des mondes de Valcombe (`config/valcombe/mondes.json`) : leur sorte, leur nom, la carte qu'ils portent, leur état.
- **Sortes de mondes** :
  - le **hub** ;
  - les **mondes de partie** (la partie principale, et autant de parties d'essai qu'on veut) ;
  - les **ateliers carte** (un par carte en cours de travail) ;
  - l'**atelier structures**.
- Tous sont **plats** et paisibles : pas de monstres, pas de météo dans les ateliers, l'heure figée dans les ateliers.
- **Le monde principal de Minecraft** ne sert plus. Les joueurs arrivent au hub, et chacun retrouve sa dernière position dans chaque monde.
- **Recréer un monde de partie** le supprime et le recrée à plat, avant d'y poser la carte. Plus aucun reste possible : c'est la fin des modes « bâti » et « carré ».

### Les parties

- **Une partie par monde de partie.** La session (règles, Conteur, minuteurs, vocal, noms au-dessus des têtes, sauvegarde) est liée à son monde.
- **Commandes, écrans et vues** visent la partie du monde où se trouve le joueur. Depuis le hub, le menu propose les parties.
- **Le vocal** est séparé par partie : on n'entend jamais une autre partie.
- **Chaque partie est enregistrée** (en cours, en pause, d'essai) et reprend au redémarrage. « Mettre en suspens » gèle tout et renvoie les joueurs au hub.

### Les ateliers

- **Atelier carte** : ouvrir une carte de la bibliothèque (ou une neuve, ou un village généré) dans son propre monde. On y construit avec WorldEdit et on y pose les repères avec les outils Valcombe : trônes, maisons, centre, salle de convocation. « Enregistrer » met à jour la bibliothèque (avec un enregistrement automatique). « Envoyer à la partie » recrée le monde de la partie choisie et y pose la carte.
- **Atelier structures** : toutes les structures, fournies et les vôtres, posées sur des parcelles alignées, chacune avec son nom.
  - On en modifie une sur place et on clique « Enregistrer ».
  - Une structure fournie modifiée remplace l'originale partout ; « Revenir à l'originale » la restaure.
  - « Nouvelle structure » ouvre une parcelle vide.
- **WorldEdit** : ajouté au modpack et au serveur, autorisé seulement dans les ateliers (et pour les invités de l'opérateur). Valcombe le complète :
  - une baguette dessinée pour le mod ;
  - la sélection affichée en 3D avec ses dimensions ;
  - « Sélection → structure du catalogue » ;
  - ses annulations reliées à celles des repères.
  
  Les outils maison qui font doublon (sélection, remplir, copier, coller…) sont retirés.
- **Écrans** : un Atelier en jeu repensé autour de quatre entrées (Cartes, Structures, Parties, Rôles et scénarios), avec des listes à vignettes et des parcours guidés. L'Atelier web garde les rôles et les scénarios et suit les mêmes parcours.

### Le serveur

- **API d'administration dans le mod**, protégée par une clé. Elle donne :
  - l'état, les joueurs et la console en direct ;
  - l'envoi de commandes ;
  - les mondes et les parties ;
  - les sauvegardes et les réglages.
- **Sauvegardes** : automatiques (mondes et créations, à intervalle réglable, les plus anciennes effacées) et restaurées d'un clic.
- **Page Serveur du launcher**, pour le serveur de ce PC comme pour un serveur distant :
  - démarrer, arrêter, redémarrer ;
  - les joueurs, la console, les mondes et les parties, les sauvegardes ;
  - les réglages : liste blanche, opérateurs, mémoire, message d'accueil, port.
- **SSH pour un VPS** : installer, mettre à jour, démarrer et redémarrer le serveur même s'il est arrêté. L'API du mod fait le reste, par un tunnel SSH.

### La haute résolution

- **Une boîte à outils d'interface** dessine à la résolution réelle de l'écran, sans les gros pixels :
  - une police lisse (fichier TTF libre de droits, inclus dans le mod) ;
  - des cadres, boutons, listes, champs et défilements nets.
- **Tous les écrans de Valcombe** passent dessus, du menu J au grimoire.
- **Les textures du mod** (jetons, emblèmes, outils, trônes, objets) sont redessinées en 128 px par les scripts qui les produisent déjà.

## Les sprints

Trois pistes avancent en parallèle dès le départ, parce qu'elles touchent des fichiers différents :

- **A** : mondes, parties et ateliers ;
- **B** : serveur et launcher ;
- **C** : haute résolution.

| Sprint | Piste | Tickets (en parallèle dans un sprint) | Dépend de |
| --- | --- | --- | --- |
| **10 · Fondations des mondes** | A | Gestionnaire de mondes (Fantasy, mondes plats enregistrés) ; hub et voyages entre mondes ; cartes de la bibliothèque posées dans n'importe quel monde ; WorldEdit et Fantasy dans le modpack, le serveur et le launcher | Rien |
| **11 · La partie dans son monde** | A | Le jeu lié à un monde (fin du monde unique) ; « Envoyer à la partie » et village généré sur un monde plat neuf ; retrait du rasage bâti/carré et de la logique « une seule carte » | 10 |
| **12 · Plusieurs parties** | A | Une session par monde de partie ; vocal, équipes et noms par partie ; enregistrement, suspens et reprise de toutes les parties ; tableau des parties au hub | 11 |
| **13 · Ateliers et WorldEdit** | A | Atelier carte ; atelier structures (parcelles, remplacer, revenir à l'original) ; WorldEdit complété et limité aux ateliers ; invitations à construire | 10 (12 pour « Envoyer » vers une partie d'essai) |
| **14 · Serveur** | B | API d'administration ; sauvegardes ; page Serveur du launcher ; SSH pour VPS | Rien (les mondes s'ajoutent au panneau après 12) |
| **15 · Haute résolution** | C | Boîte à outils et police ; textures en 128 px ; tous les écrans passés dessus | Rien |
| **16 · Atelier refait** | A + C | Atelier en jeu à quatre entrées sur la nouvelle boîte à outils ; Atelier web aligné | 13, 15 |
| **17 · Recette et publication** | Toutes | Test en jeu complet (captures de chaque écran), tutoriel et guide, version 0.14.0 | Tout |

Dans chaque sprint, chaque ticket est confié à un agent exécutant qui travaille dans sa propre copie du dépôt (un worktree), sur sa propre branche. Un agent orchestrateur par sprint :
- découpe le travail et lance les exécutants ;
- vérifie chaque résultat (compilation, tests, test en jeu avec captures) ;
- fusionne, puis passe au sprint suivant.

## Comment vous suivez l'avancement

- **Dans Cap**, au fil de l'eau :
  - chaque ticket passe d'une colonne à l'autre : « Sprint en cours », « En cours », « À tester en jeu », « Terminé » ;
  - ses notes disent ce qui est fait, la branche et le commit, les captures et ce qui reste ;
  - les catégories « Sprint 10 · … » à « Sprint 17 · … » regroupent les tickets.
- **Dans l'application**, la vue des workflows montre les agents en train de travailler.
- **À la fin de chaque sprint**, un compte rendu court (fait, vérifié, reste à faire, captures) est ajouté au pense-bête Cap « Suivi des sprints 10 à 17 ».

## Risques et ce que je ferai

- **Le passage à plusieurs parties** touche les deux plus gros fichiers (`Session.java`, 3 700 lignes ; `McHost.java`, 2 900 lignes). Il passe en premier dans la piste A, avec des tests qui jouent deux parties en même temps.
- **Les vieilles sauvegardes de partie** (monde unique) ne reprennent pas dans les nouveaux mondes : une partie en cours au moment de la mise à jour est perdue. La bibliothèque, les structures, les rôles et les scénarios sont gardés.
- **Votre serveur personnel** (ports 25565, 25570, 24454) n'est jamais touché par les tests : le serveur d'essai du test en jeu utilise ses propres ports.
- **La version publiée** (0.14.0) arrive à la fin du sprint 17 : les joueurs ne voient rien de cassé entre-temps.
