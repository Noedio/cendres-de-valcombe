# Les Cendres de Valcombe — feuille de route

Objectif : passer d'un prototype qui fonctionne à un jeu **réellement agréable à jouer entre amis**, immersif et facile à mener pour le Conteur. Tout reste original : noms, textes, visuels et sons.

L'ordre des phases suit ce qui manque le plus pour jouer. Chaque phase se livre en une version publiée ; le launcher la récupère tout seul.

## Phase 1 — Une vraie partie, du début à la fin (priorité)

Ce qui manque pour qu'une soirée se déroule sans friction.

| Domaine | Ajout | Pourquoi |
| --- | --- | --- |
| Vote | **Vote à l'horloge** : une aiguille fait le tour du cercle, chaque joueur lève ou baisse la main à son tour, le compte s'affiche en direct | C'est le cœur du jeu d'origine ; le vote simultané actuel perd toute la tension |
| Jour | Déroulé guidé : discussion libre (minuteur), cloche de rassemblement, nominations, temps de défense de l'accusé, vote | Le Conteur n'a plus à tout orchestrer à la main |
| Communication | **Chuchotements** : aller parler en privé est annoncé publiquement (« X chuchote avec Y »), sans le contenu | Règle importante du jeu d'origine, source de soupçons |
| Communication | Salles privées : entrer dans une maison place automatiquement les occupants dans un groupe vocal privé | Les apartés deviennent naturels |
| Morts | Fantômes : apparence translucide, nom grisé, jeton de vote de fantôme visible, vocal réduit | On voit d'un coup d'œil qui est mort et qui peut encore voter |
| Aube | Annonce des morts mise en scène : glas, titre à l'écran, pierre tombale à la place du siège | Moment fort de chaque matinée |
| Conteur | **Décisions en direct** : quand un pouvoir dépend du Conteur (enregistrement trompeur, détournement du Châtelain), la nuit se met en pause et le panneau lui demande | Remplace les consignes réglées à l'avance |
| Conteur | Tuer, ressusciter, empoisonner, rendre ivre, échanger un rôle en cours de partie, annuler la dernière action | Le Conteur doit pouvoir rattraper toute erreur |
| Conteur | Sauvegarde automatique de la partie : un redémarrage du serveur ne la perd plus | Indispensable sur une longue soirée |
| Salon | Une salle d'attente à côté du village : panneaux de règles, fiches des rôles, inscription en marchant sur une plaque | On ne commence plus au milieu de la place |
| Launcher | Adresse du serveur fournie par la version publiée, état du serveur sur l'accueil (en ligne, joueurs connectés) | Plus rien à saisir pour les joueurs |

### Avancement de la phase 1

- Fait : vote à l'horloge, défense, nominations, cloche de rassemblement, chuchotements annoncés, aube et accusations mises en scène, fantômes, pouvoirs du Conteur, décisions du Conteur en direct, sauvegarde automatique, salle d'attente, état du serveur dans le launcher.
- Reporté : salons vocaux automatiques dans les maisons (les groupes intégrés à Simple Voice Chat, touche V, permettent déjà de se parler en privé).

## Phase 2 — Immersion et visuel

| Domaine | Ajout |
| --- | --- |
| Pack de ressources | Envoyé automatiquement par le serveur : sons originaux (cloche, vent, battements de cœur la nuit), musiques d'ambiance, police d'icônes de rôles pour le chat |
| Sièges | Les joueurs sont vraiment **assis** sur leur siège ; trône sculpté du Conteur au centre |
| Jetons dans le monde | Jetons flottants au-dessus des sièges (vivant, mort, vote de fantôme, nominé) ; pour le Conteur seul, rôles et marqueurs visibles en jeu |
| Nuit | Séquence de réveil : fondu au noir, titre « Tu te réveilles », son ; ciel nocturne et brume, lanternes qui s'éteignent |
| Exécution | Courte scène : l'accusé est conduit au gibet ou au bûcher au centre de la place, foule silencieuse, glas |
| Fin de partie | Révélation : tous les jetons se retournent au-dessus des têtes, le camp vainqueur est célébré, historique illustré |
| Interface | Écrans encore plus travaillés : animations d'ouverture, carte de rôle qui se retourne au début, curseurs et listes aux couleurs de Valcombe (launcher compris) |

### Avancement de la phase 2

- Fait : jetons flottants au-dessus des sièges (nom, mort, vote de fantôme, accusé), effet « Tu te réveilles », exécution mise en scène (fumée, flammes d'âme, glas), révélation des rôles et célébration en fin de partie.
- Reporté : joueurs réellement assis (conflit avec l'immobilité de nuit), sons et musiques originaux (pack de ressources), écrans animés.

## Phase 3 — Contenu : cartes et rôles

| Domaine | Ajout |
| --- | --- |
| Cartes | Trois lieux construits à la main (bourg gothique, monastère dans la brume, port de pêche), au choix du Conteur avant la partie |
| Outils de carte | Baguette d'édition : clic sur un bloc pour poser un siège, une maison, la salle d'attente ; import de constructions (fichiers de structure) |
| Rôles | Nouveaux scripts de rôles originaux, plus avancés, à jouer après le premier |
| Rôles | Voyageurs (joueurs qui arrivent ou partent en cours de partie) et figures neutres pour les très grands groupes |
| Joueurs | **Carnet du joueur** : noter qui prétend être quoi, ses soupçons, l'historique des votes |
| Prise en main | Partie d'entraînement guidée avec des joueurs fictifs, pour apprendre les règles |

### Avancement de la phase 3

- Fait : trois thèmes de village générés (`/vc carte construire gothique|monastere|port`, ou bouton « Thème » du panneau), salle d'attente bâtie avec le village, baguette d'édition (`/vc carte baguette` : clic droit sur un bloc pour poser un siège, accroupi pour une maison), carnet du joueur (bouton « Mon carnet », enregistré sur le PC).
- Reporté : nouveaux scripts de rôles originaux, voyageurs, partie d'entraînement guidée (les parties avec bots restent possibles via `/vc bots`), import de fichiers de structure.

## Phase 4 — Confort et vie de la communauté

| Domaine | Ajout |
| --- | --- |
| Conteur | **Grimoire sur tablette ou téléphone** : page web servie par le serveur, pour mener la partie sans quitter l'écran du jeu |
| Communauté | Compte rendu de partie envoyé sur Discord, statistiques par joueur (victoires, rôles joués) |
| Hébergement | Script d'installation sur un petit serveur loué, sauvegardes automatiques |
| Launcher | Plusieurs serveurs enregistrés, rapport de plantage en un clic |

### Avancement de la phase 4

- Fait : grimoire sur tablette ou téléphone (`/vc tablette` : page web servie par le serveur, lien à jeton secret, actions avec les droits du Conteur), compte rendu de fin de partie sur Discord (`/vc discord <webhook>`, `/vc discord essai`), statistiques par joueur (`/vc stats [pseudo]`), guide d'hébergement sur VPS ([HEBERGEMENT.md](HEBERGEMENT.md)) avec service systemd et sauvegardes automatiques.
- Corrigé au passage : la révélation des rôles sur les jetons en fin de partie (les sièges étaient oubliés trop tôt).
- Reporté : plusieurs serveurs enregistrés et rapport de plantage dans le launcher.

## État des lieux après la phase 4 (novembre 2026)

**Ce qui tient** : les règles des 22 rôles, une partie complète du salon à la révélation, le vote à l'horloge, le Conteur humain avec ses pouvoirs et la tablette, la sauvegarde automatique, le launcher qui se met à jour seul. 44 tests automatiques et un test en jeu complet passent.

**Ce qui manque le plus** :

| Constat | Conséquence |
| --- | --- |
| Aucune vraie soirée jouée à 8–12 joueurs en réseau | Latence, vocal, durée des étapes et ergonomie réelle du Conteur restent inconnus. C'est le premier risque. |
| Le monde est fait de blocs ordinaires, de textes flottants et de particules : le mod n'apporte aucun modèle 3D ni aucun son | Le village a l'air d'une maquette ; le jeu d'origine vit de ses jetons, de son grimoire et de son ambiance |
| Le vote, la nomination et l'exécution se jouent dans des menus | On regarde l'écran au lieu de regarder les autres joueurs |
| Le vocal ne suit pas le jeu (pas de salon Conteur ↔ joueur réveillé, pas de salles privées) | Le Conteur doit tout écrire ; les apartés ne sont pas naturels |
| Session.java fait 1 576 lignes ; un seul long scénario de test en jeu ; aucune intégration continue | Chaque ajout devient plus risqué |
| La tablette passe en HTTP simple ; les rôles se limitent à un seul script | Limites connues, acceptables entre amis pour l'instant |

Reportés des phases précédentes : salons vocaux dans les maisons, joueurs assis, sons et musiques, écrans animés, nouveaux scripts de rôles, voyageurs, partie d'entraînement guidée, import de structures, plusieurs serveurs et rapport de plantage dans le launcher.

## Comment faire de la 3D dans Minecraft

Pas besoin de moteur à part : trois outils suffisent, du plus simple au plus riche.

1. **Entités d'affichage** (`block_display`, `item_display`, `text_display`), pilotées par le serveur. Elles se déplacent, tournent et changent d'échelle avec une **interpolation fluide** côté client. Tout le monde les voit, même sans le mod client. Idéal pour l'horloge de vote, les jetons et le gibet.
2. **Modèles et textures du mod** (fichiers JSON de modèles 3D, faits avec Blockbench ou générés par script comme les textures actuelles), affichés par des `item_display`. Exemples : trônes sculptés, jetons en relief, lutrin et grimoire, cloche.
3. **Rendu client du mod Fabric** : effets réservés aux joueurs qui ont le mod (brouillard et ciel de nuit, contour lumineux de l'accusé, animation du bras levé, caméra cinématique, carte de rôle qui se retourne en 3D à l'écran).

Les sons et musiques passent par le mod (`sounds.json` et fichiers `.ogg`). Ils doivent être originaux, créés ou générés par nous.

## Phase 5 — Le village en 3D (visuels)

| Ajout | Ce que voient les joueurs | Outil | Effort |
| --- | --- | --- | --- |
| **Horloge de vote géante** au centre du cercle | Une grande aiguille tourne de siège en siège. Une flamme s'allume au-dessus de chaque main levée, et le compteur grandit au sol. | 1 | Moyen |
| **Jetons de rôle en relief** au-dessus des sièges | Disques sculptés qui tournent lentement. Côté pile pendant la partie, ils se **retournent** à la révélation pour montrer l'emblème du rôle. Un crâne apparaît à la mort, un cierge pour le vote de fantôme. | 1 + 2 | Moyen |
| **Trônes sculptés et joueurs assis** | Chacun s'assoit vraiment sur son siège. L'immobilité de nuit est assurée par la monture, ce qui règle le conflit qui bloquait cette idée. | 1 + 2 | Moyen |
| **Gibet au centre et exécution mise en scène** | Le gibet sort du sol à l'annonce du vote. La cloche du beffroi se balance et sonne, puis la caméra des joueurs moddés se tourne vers l'accusé. | 1 + 3 | Moyen |
| **Grimoire physique du Conteur** | Un lutrin portant un livre ouvert devant le siège du Conteur. Un clic dessus ouvre le grimoire. | 2 | Petit |
| **Nuit qui tombe vraiment** | Brume violette, lune pâle, lanternes du village qui s'éteignent une à une. Elles se rallument à l'aube. | 1 + 3 | Moyen |
| **Fantômes** | Les morts deviennent translucides, avec une traîne de particules d'âme. Leur jeton porte le cierge tant que le vote de fantôme reste. | 3 | Moyen |
| **Carte de rôle à l'écran** | Au début de la partie, une carte en 3D tombe face cachée puis se retourne, avec le son d'un parchemin. | 3 | Petit |
| **Sons et musiques originaux** | Ambiance du jour (vent, corbeaux), ambiance de nuit, tic-tac de l'horloge de vote, glas, thème de victoire. Ils sont générés par script puis mixés. | 2 | Moyen |
| **Décor par thème** | Feuillage, bannières, puits et étals propres à chaque thème (gothique, monastère, port), plus quelques modèles 3D de décor | 1 + 2 | Moyen |

### Avancement de la phase 5

- Fait :
  - **horloge de vote géante** : une aiguille dorée au-dessus du puits, qui tourne de siège en siège avec son tic-tac ; le compte des voix flotte au-dessus ;
  - **mains levées** : une flamme bleue pour une intention, orange pour une voix comptée ;
  - **gibet** : il sort du pavé devant l'accusé, en grinçant, puis redescend ;
  - **jetons en relief** au-dessus des sièges (dos à la tour de Valcombe, crâne à la mort, cierge pour le vote de fantôme), qui **se retournent** un à un à la révélation pour montrer l'emblème du rôle ;
  - **trônes sculptés** et **joueurs assis** (de nuit, on ne peut pas se lever) ;
  - **lutrin du Conteur** au centre : un clic ouvre le grimoire ;
  - **nuit** : brume et ciel assombri, lanternes du village qui passent une à une à la flamme d'âme ;
  - **fantômes** translucides avec une traînée d'âme ;
  - **carte de rôle** qui tombe et se retourne à l'écran au début de la partie ;
  - **sons et musiques originaux**, synthétisés par script : cloche, glas, tic-tac, réveil, parchemin, gibet, ambiances de jour et de nuit, thèmes de victoire.
- Réglage `effets reduits` pour les petites machines : sans flammes, gibet ni brume.
- Reporté : décor 3D propre à chaque thème (bannières, étals, puits).

## Phase 6 — Jouer avec son corps, pas avec des menus

| Ajout | Détail | Effort |
| --- | --- | --- |
| **Nominer en pointant** | Viser un joueur et faire un clic droit avec la « main accusatrice » (objet du mod). Un rayon de lumière relie l'accusateur à l'accusé, et le menu ne sert plus qu'à confirmer. | Moyen |
| **Lever la main pour voter** | Au passage de l'aiguille, appuyer sur une touche (ou sneak). Le bras se lève sur le modèle du joueur, visible de tous. | Moyen |
| **Vocal piloté par le jeu** | La nuit, un groupe vocal temporaire relie le Conteur et le joueur réveillé. Le jour, les salles privées (maisons, recoins) créent un groupe en entrant. Les morts gardent la parole, mais avec un écho. | Grand |
| **Chuchotements visibles** | En plus de l'annonce, une bulle « … » apparaît au-dessus des deux joueurs tant qu'ils sont à l'écart | Petit |
| **Minuteur de discussion** | Barre de boss « Discussion libre — 4:30 » réglable par le Conteur. Une cloche sonne à la fin. | Petit |
| **Spectateurs** | Rejoindre une partie en cours comme observateur, sans voix et sans grimoire. Une option permet de voir le grimoire pour apprendre. | Moyen |
| **Chronique de la partie** | Écran de fin en frise : nuits, morts, nominations, votes (qui a levé la main) et rôles. On peut le relire depuis le menu. | Moyen |
| **Titres et trophées** | À partir des statistiques, des titres apparaissent sous le nom (« Démon redouté », « Œil de lynx »). Ils sont purement cosmétiques. | Petit |
| **Partie d'entraînement guidée** | Un joueur seul face à des joueurs fictifs, avec des bulles qui expliquent chaque étape | Grand |

### Avancement de la phase 6

- Fait :
  - **plume d'accusation** : viser un joueur, clic droit, et un trait de lumière relie l'accusateur à l'accusé ;
  - **touche H** pour lever ou baisser la main pendant le vote ;
  - **vocal piloté par le jeu** : la nuit, seuls le Conteur et le joueur réveillé se parlent ; le jour, une maison est une salle privée ;
  - **bulles** au-dessus de ceux qui chuchotent ;
  - **minuteur de discussion** (barre en haut de l'écran, cloche à la fin) ;
  - **spectateurs**, avec en option la vue du grimoire ;
  - **chronique** de la partie, à relire depuis le menu ;
  - **titres** tirés des statistiques, affichés sous le nom ;
  - **partie d'entraînement guidée** avec six joueurs fictifs.
- Reporté : bras réellement levé sur le modèle du joueur (les flammes en tiennent lieu), écho sur la voix des morts.

## Phase 7 — Contenu

| Ajout | Détail | Effort |
| --- | --- | --- |
| Deuxième et troisième scripts | Deux scripts originaux de rôles, plus retors (informations fausses, morts multiples, Démons différents), avec leurs textes et emblèmes | Grand |
| Voyageurs | Joueurs qui arrivent ou partent en cours de partie, avec un rôle à part, pour les soirées où l'on entre et sort | Moyen |
| Figures neutres | Rôles hors camp pour les grands groupes (15 et plus) | Moyen |
| Jetons de rappel du Conteur | Marqueurs libres (« empoisonné », « protégé », « ivre », « a choisi X ») posés depuis la tablette et visibles en 3D sur le grimoire physique | Moyen |
| Import de structures | Charger un fichier `.nbt` (village fait à la main par un ami) puis y placer sièges et maisons avec la baguette | Petit |

### Avancement de la phase 7

- Fait :
  - **deuxième script, « Les Brumes de Valcombe »**, avec trois rôles originaux : le Sonneur (combien de mauvais parmi deux joueurs), le Cartographe (distance du Démon) et la Faussaire (Sbire qui fausse les informations d'un joueur pour la nuit) ;
  - **jetons de rappel** du Conteur, posés depuis le grimoire ou la tablette ;
  - **import de constructions** `.nbt` comme carte (`/vc carte importer`).
- Reporté : troisième script, voyageurs, figures neutres, rappels visibles en 3D (un objet du monde est vu par tous les joueurs, pas seulement par le Conteur).

## Phase 8 — Solidité, tests réels et confort

| Ajout | Détail | Effort |
| --- | --- | --- |
| **Soirée de test à 8–12 joueurs** | Grille d'observation (durées, bugs, ressenti du Conteur), puis correctifs. À faire **avant** les phases 5 à 7, puis à refaire après chacune. | Petit |
| Découpage du code | Séparer Session.java en morceaux (vote, nuit, Conteur, vue), plusieurs scénarios de test en jeu indépendants | Moyen |
| Intégration continue | Tests lancés automatiquement à chaque envoi sur GitHub | Petit |
| Tablette sécurisée | Accès par un relais chiffré (HTTPS), ou proposé seulement sur le réseau local | Moyen |
| Launcher | Plusieurs serveurs, rapport de plantage en un clic, réglage de la mémoire, curseurs et listes aux couleurs de Valcombe | Moyen |
| Performances | Mesurer le coût des entités d'affichage et des particules à 15 joueurs ; prévoir un réglage « effets réduits » | Petit |

### Avancement de la phase 8

- Fait :
  - **découpage du code** : la vue envoyée aux écrans quitte Session.java, et la scène 3D est une classe à part ;
  - **mises à jour ciblées** : les jetons, étiquettes et flammes ne sont modifiés que s'ils changent, sans recréer d'entités ;
  - **intégration continue** : tests du mod et compilation du launcher à chaque envoi sur GitHub ;
  - **tablette réservée au réseau local** par défaut ;
  - **launcher** : plusieurs serveurs enregistrés et rapport de plantage en un clic ;
  - **47 tests automatiques**, dont 40 parties complètes jouées avec le script des Brumes, et un test en jeu qui photographie chaque écran et la scène 3D.
- Reste à faire : la **soirée de test réelle** à 8–12 joueurs.

## Ordre conseillé

1. **Soirée de test réelle** avec la 0.3.0 : c'est elle qui dira quoi corriger en premier.
2. **Phase 5, premier lot**, ce que tout le monde voit, même sans le mod : horloge de vote géante, gibet, jetons qui se retournent, joueurs assis, sons.
3. **Vocal piloté par le jeu** (phase 6) : c'est le plus gros gain de confort pour le Conteur.
4. Le reste de la phase 6, puis les phases 7 et 8, au fil des retours des joueurs.

## Déjà fait

- Moteur de règles des 22 rôles, testé.
- Écrans sur mesure, affichage en jeu, village généré, Conteur humain, éditeur de scripts, vocal coupé la nuit.
- Monde plat sans créatures.
- Launcher portable : connexion Microsoft intégrée, téléchargement du jeu, et mise à jour automatique de lui-même à partir de la version 0.2.2.
