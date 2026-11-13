# Atelier carte

Un monde de Valcombe par carte en cours de travail (sorte `atelier-carte`) : plat, paisible, midi fixe. On y bâtit avec WorldEdit, on y pose les repères avec les outils de Valcombe, et la carte s'enregistre dans la bibliothèque, avec des versions pour revenir en arrière. Le code est dans `fr.valcombe.mc.MapWorkshop`.

## Le lien atelier et carte

- La fiche du monde dans le registre (`config/valcombe/mondes.json`) porte l'identifiant de la carte de la bibliothèque (champ `carte`). Il est réservé dès l'ouverture, même pour une carte vierge pas encore enregistrée.
- **Un seul atelier par carte** (`AtelierLinks.atelierOf`) : ouvrir une carte déjà en atelier mène à cet atelier.
- Un atelier créé à la main (`/vc monde creer atelier-carte <nom>`) reçoit une carte neuve à son nom à la première visite.
- « Enregistrer sous » change la carte que porte l'atelier (et son nom) : l'atelier continue sur la copie.

## Ouvrir

| Depuis | Bibliothèque | Carte vierge | Village généré |
| --- | --- | --- | --- |
| Menu J › Mondes › « Ouvrir une carte… » | onglet Bibliothèque | onglet Carte vierge | onglet Village généré |
| Commande (opérateurs) | `/vc atelier carte ouvrir <carte>` | `/vc atelier carte nouvelle <nom>` | `/vc atelier carte village <trônes> [<taille> <thème> <oui\|non> <nom>]` |
| Atelier en jeu | onglet Cartes, « Ouvrir dans un atelier » | | onglet Village, « Générer dans un atelier » |
| Atelier web | « Ouvrir dans un atelier » sur chaque carte (route `carte/atelier`) | | |

La carte de la bibliothèque est posée au centre d'un monde neuf (`MapPlacer.place`) ; le village est bâti par petits lots (`MapPlacer.build`), puis sa salle de convocation et sa salle d'attente. Une barre suit l'avancement ; celui qui a ouvert l'atelier y est conduit à la fin (depuis l'Atelier web : un lien « [Y aller] » dans le chat).

## Dans l'atelier

- **Entrée** : mode créatif et baguette (WorldEdit, voir `WorldEditGate`), les sept outils « Carte : … », un mot d'accueil avec ses liens. Les outils sont repris en sortant (sauf en mode construction).
- **Repères** : les outils, le menu de la main (`/vc objet`), `/vc carte trone|maison|centre|retirer|annuler|ordonner|infos` visent la carte de l'atelier où l'on est. L'outil « Carte : structure » pose les structures du catalogue ; une maison ou une salle pose son repère avec elle. `/vc annuler` défait aussi les repères de l'atelier (pile « repères » de `SharedUndo`, une pile par atelier).
- **Ce qu'on voit** : une étiquette flottante par repère (tag `vc_ac`), un contour au sol, le bord de la zone de la carte, un panneau au-dessus de la place (compte, verdict, état d'enregistrement) et le compte au-dessus de la barre d'objets (`MapTally`).
- **Invités** : ils bâtissent et posent les repères ; ouvrir, enregistrer, envoyer et fermer sont pour les opérateurs.

## Enregistrer, versions

- **Capture** : `MapPlacer.shoot` prépare la carte (zone bâtie autour de la place, repères, réglages) sans l'écrire ; `MapPlacer.signature` en donne l'empreinte ; `MapPlacer.keep` la range dans la bibliothèque et note que le monde la porte. Rien n'est écrit quand l'empreinte n'a pas changé.
- **Zone cherchée** : l'emprise de la carte plus 48 blocs, et aussi loin que les constructeurs sont allés (au plus 200 blocs du centre).
- **Automatique** : toutes les 5 minutes après un changement vu (blocs posés ou cassés, WorldEdit, repères, structures), à l'arrêt du serveur, à la fermeture, et avant qu'un atelier ne soit supprimé ou remis à neuf autrement (`/vc monde supprimer`, API d'administration : garde `WorldManager.Guard`).
- **Versions** (`MapVersions`) : `config/valcombe/creations/cartes/versions/<carte>/<date>/` (fiche, blocs, aperçu, `version.json`). 10 versions automatiques et 20 versions demandées par carte au plus, comptées à part. « Revenir à la version du … » garde d'abord l'état présent, remet la version dans la bibliothèque et la repose dans l'atelier. Une carte supprimée de la bibliothèque emporte ses versions ; une carte ouverte dans un atelier ne se supprime pas (on ferme d'abord l'atelier, qui l'enregistre).

## Envoyer, fermer

- **Envoyer à la partie** : la carte doit être jouable (5 trônes au moins) ; elle est enregistrée, puis envoyée (`MapSender.sendMap`) au monde de partie choisi (la partie principale ou une partie d'essai). L'atelier reste ouvert.
- **Fermer l'atelier** : enregistrement, puis suppression du monde (`WorldManager.delete`) ; ceux qui s'y trouvent vont au hub.

## Fichiers du monde de l'atelier

| Fichier | Contenu |
| --- | --- |
| `valcombe-atelier.json` | les repères et réglages de la carte en travail (fiche de village) |
| `valcombe-atelier-etat.json` | empreinte du dernier enregistrement, date, portée des constructeurs, pose en cours |
| `valcombe-carte.json` | la carte posée ou capturée en dernier (`MapPlacer`) |

Une pose coupée par un arrêt du serveur reprend au démarrage (champ `pose`).

## Vues pour les écrans

- `ateliersCarte` (opérateurs) : ateliers ouverts, cartes de la bibliothèque (avec l'atelier qui les porte), thèmes et bornes d'un village.
- `atelierCarte` (dans un atelier carte) : nom, carte, compte, verdict, ce qui manque, état, versions, mondes de partie, dernier message.

## Poser suit la règle de casser

`BuildAccess.mayModify` décide pour les deux gestes : la casse (événement de Fabric), la pose (`PlaceGuardMixin` sur `ItemStack.useOn`) et les seaux (`BucketGuardMixin` sur `Player.mayUseItemAt`). Un opérateur en survie au hub ou dans une partie ne pose plus rien, comme il ne cassait rien.
