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

## Phase 4 — Confort et vie de la communauté

| Domaine | Ajout |
| --- | --- |
| Conteur | **Grimoire sur tablette ou téléphone** : page web servie par le serveur, pour mener la partie sans quitter l'écran du jeu |
| Communauté | Compte rendu de partie envoyé sur Discord, statistiques par joueur (victoires, rôles joués) |
| Hébergement | Script d'installation sur un petit serveur loué, sauvegardes automatiques |
| Launcher | Plusieurs serveurs enregistrés, rapport de plantage en un clic |

## Déjà fait

- Moteur de règles des 22 rôles, testé.
- Écrans sur mesure, affichage en jeu, village généré, Conteur humain, éditeur de scripts, vocal coupé la nuit.
- Monde plat sans créatures.
- Launcher portable : connexion Microsoft intégrée, téléchargement du jeu, et mise à jour automatique de lui-même à partir de la version 0.2.2.
