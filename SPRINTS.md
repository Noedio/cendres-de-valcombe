# Les Cendres de Valcombe — plan en sprints

Chaque sprint se termine par : compilation, tests au vert, et un commit Git local.

| Sprint | Objectif | Livrable | État |
| --- | --- | --- | --- |
| S0 | Socle | Dépôt Git, ce plan | En cours |
| S1 | Mod client et réseau | Paquets serveur ↔ client ; le serveur ouvre les écrans sur mesure si le joueur a le mod, sinon les menus coffre | À faire |
| S2 | Direction artistique | Palette gothique, textures originales : parchemin, cadres, boutons, 22 jetons de rôle, marqueurs | À faire |
| S3 | Écrans sur mesure | Menu et salon, carte de rôle, grimoire en cercle, choix de nuit sur le cercle, information, vote, panneau du Conteur, fiches | À faire |
| S4 | HUD et ambiance | Bandeau de phase, compte à rebours du vote, voile de nuit, sons | À faire |
| S5 | Carte de Valcombe | Générateur de village : place, cercle de sièges, maisons nominatives, ruelles ; nuit « chacun chez soi » | À faire |
| S6 | Conteur humain complet | Distribution manuelle, faux présage, décisions d'enregistrement et du Châtelain, marqueurs et notes | À faire |
| S7 | Vocal de proximité | Simple Voice Chat : muet la nuit, Conteur qui chuchote, groupes privés | À faire |
| S8 | Éditeur de scripts | Choisir les rôles d'une partie, sauvegarder des scripts | À faire |
| S9 | Modpack et launcher | Fichier .mrpack (Modrinth App, Prism), serveur prêt à lancer, guide d'installation | À faire |

## Principes

- Les règles vivent dans `fr.valcombe.rules` et la table de jeu dans `fr.valcombe.session`, sans dépendance à Minecraft. Elles sont testées hors du jeu.
- Toute l'interface (écrans, textures, noms) est originale. Rien n'est repris de l'esthétique ni des textes du jeu de plateau.
- Un joueur sans le mod client peut quand même jouer, avec les menus coffre et le chat.
