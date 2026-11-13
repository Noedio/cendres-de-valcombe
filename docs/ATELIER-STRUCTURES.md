# Atelier structures

Un monde de Valcombe (sorte `atelier-structures`, un seul) où **toutes les structures** du catalogue, fournies avec le mod et créées par vous, sont posées chacune sur sa parcelle. On les y modifie sur place, et ce qu'on enregistre est gardé.

## Y aller

- Menu J, « Mondes », « Atelier structures », « Y aller » : l'atelier est créé à la première visite (il est proposé même avant d'exister).
- Ou `/vc structures atelier`.
- Ou, depuis l'Atelier web, « Aller à l'atelier des structures », et « Voir dans l'atelier » sur une structure (devant sa parcelle).
- **Première visite** : on arrive en vol au-dessus de l'accueil, un peu en retrait au nord-ouest, le regard plongeant sur les rangées : toutes les parcelles sont en vue (le point de vue recule pour un très grand catalogue, `StructurePlots.overview`). On descend ensuite où l'on veut ; les visites suivantes reprennent la dernière position.
- Réservé aux opérateurs et, quand les invitations seront branchées, à leurs invités (voir « Droits »).

## Ce qu'on y trouve

- **Une parcelle par structure** : un carré centré sur l'origine de la structure (la colonne où se tiendraient les pieds de celui qui la pose), assez grand pour la tourner sur place et l'agrandir un peu.
  - Sol quadrillé : béton gris clair, une ligne blanche tous les 4 blocs depuis l'origine.
  - Bordure d'ardoise polie autour.
- **Des rangées par catégorie**, du nord au sud : maisons et salles, pour la place, décor, vos structures. Le nom de la rangée flotte au bout ouest.
- **Une allée** au nord de chaque rangée, avec le **pupitre** (lutrin) de chaque parcelle et un panneau flottant : nom, rangée, état (« fournie », « modifiée », « créée », « nouvelle »).
- **L'accueil**, au point d'arrivée : un pupitre qui ouvre la liste de toutes les parcelles et « Nouvelle structure ».

Une structure ajoutée au catalogue ailleurs (éditeur, import, pack, sélection WorldEdit) reçoit sa parcelle au prochain passage (toutes les 5 secondes quand quelqu'un est dans l'atelier, et à chaque `/vc structures atelier`). Les autres parcelles ne bougent jamais : la disposition est enregistrée avec le monde.

## Modifier une structure

1. Bâtissez sur la parcelle, à la main ou avec WorldEdit.
2. Au pupitre (clic droit), « Enregistrer ». L'intérieur de la bordure est capturé :
   - le quadrillage du sol (au niveau du sol plat et dessous) ne compte pas ; un trou creusé dedans, lui, fait partie de la structure ;
   - ce qui dépasse la bordure n'est pas enregistré ;
   - l'origine reste l'ancre de la structure (là où se tiennent les pieds à la pose).
3. Une **structure fournie** modifiée devient une **surcharge** : elle remplace l'originale **partout**, dans le village généré (maisons, salle de convocation, pavillon, monument du centre, étals, arbres, carrés de simples, tonneaux, lampadaires), l'éditeur de carte, l'Atelier web et en jeu, et les cartes qui la posent ensuite.
4. **« Revenir à l'originale »** retire la surcharge (elle va à la corbeille) et repose l'originale sur la parcelle. Aussi dans l'Atelier web (bouton sur la structure).

Autres actions du pupitre :

| Action | Effet |
| --- | --- |
| Reposer | Abandonne les changements en cours : la version enregistrée revient sur la parcelle |
| Tourner | Un quart de tour autour de l'origine (sens des aiguilles d'une montre) ; « Enregistrer » garde l'orientation. Une maison fournie garde sa porte au nord (vers le pupitre) : le village la tourne vers la place |
| Renommer | Structures créées et parcelles neuves |
| Repère | Structures créées : aucun, maison, salle de convocation |
| Supprimer | Structures créées (après confirmation) : la structure va à la corbeille du catalogue, la parcelle est rasée |
| Nouvelle structure | Une parcelle vide, nommée (petite 11, moyenne 17, grande 25 ou immense 41 blocs de côté), au bout de la rangée « Vos structures » |

L'écran dit si la parcelle a des **modifications non enregistrées** (comparaison case par case avec la version posée ou enregistrée en dernier). Si une structure change ailleurs pendant que sa parcelle en a, rien n'est écrasé : la parcelle est marquée « changée ailleurs » (« Reposer » prend la version du catalogue, « Enregistrer » garde la vôtre ; la version venue d'ailleurs est alors copiée dans la corbeille). Une structure supprimée ailleurs emporte sa parcelle, sauf si celle-ci a des modifications non enregistrées : elle reste alors en parcelle neuve, marquée « changée ailleurs », et « Enregistrer » remet la structure au catalogue.

Une pose interrompue (serveur arrêté ou partie quittée pendant la barre) est reprise au retour dans l'atelier : la parcelle est reposée en entier, et les allées de même.

## Commandes

Sans identifiant, une action vise la parcelle où l'on se tient (bordure et bout d'allée compris), sinon la dernière choisie à un pupitre.

```
/vc structures atelier
/vc structures liste | aller <id> | choisir <id>
/vc structures enregistrer [id] | originale [id] | reposer [id] | tourner [id]
/vc structures renommer <nom> | repere <maison|salle|aucun> | supprimer [confirmer]
/vc structures nouvelle <petite|moyenne|grande|immense> <nom>
```

## Fichiers

| Fichier | Contenu |
| --- | --- |
| `config/valcombe/creations/structures/<id>.json`, `.nbt` | Vos structures |
| `config/valcombe/creations/structures/surcharges/<id>.json`, `.nbt` | Structures fournies modifiées |
| `config/valcombe/creations/structures/corbeille/` | Ce qui a été supprimé ou retiré, daté : rien n'est jamais effacé, on le remet à la main |
| `<monde>/dimensions/valcombe/<atelier>/valcombe-parcelles.json` | La disposition (parcelles et rangées) |
| `<monde>/dimensions/valcombe/<atelier>/valcombe-parcelles/<id>.nbt` | La version posée ou enregistrée en dernier sur chaque parcelle (référence des modifications non enregistrées) |

Remettre l'atelier à neuf (`/vc monde recreer atelier-structures`) refait la disposition à partir du catalogue ; les parcelles neuves jamais enregistrées sont perdues, le catalogue et les surcharges restent.

## Pour les autres parties du mod

- **Enregistrer une construction d'un monde** (« Sélection WorldEdit → structure du catalogue ») : `StructureStore.saveFromWorld(level, a, b, id, nom, pieds, repere)` pour une structure à vous, `StructureStore.overrideFromWorld(level, a, b, id, pieds)` pour une structure fournie (surcharge). La boîte [a, b] est capturée telle quelle, contenu des blocs compris (sans données dangereuses).
- **Lire** : `list()`, `def(id)` (pour une structure fournie modifiée : sa taille, son ancre et `modifiee = true`), `exists(id)`, `isBuiltin(id)`, `isOverridden(id)`, `signature(id)` (change à chaque enregistrement).
- **Revenir à l'originale** : `revert(id)`. Renommer, repère, supprimer : `rename`, `setMark`, `delete` (corbeille). Garder une copie de la version actuelle avant de l'écraser : `backup(id)` (corbeille, la version reste en place).
- **Droits** : `fr.valcombe.mc.worlds.BuildAccess` est le point unique « peut construire ici » (règles, WorldEdit et invitations : voir sa documentation). L'atelier structures s'en sert par `BuildAccess.mayBuildIn(joueur, monde)` et `requireBuildIn(...)` : dans un atelier, les opérateurs et les joueurs qu'ils y ont invités (`/vc atelier inviter <joueur> atelier-structures`, ou depuis l'atelier : `/vc atelier inviter <joueur>`).

## Tests

- JUnit : `StructurePlotsTest` (rangées par catégorie, rien ne bouge, places reprises, rangées pleines), `StructureOverrideTest` (surcharge, retour à l'originale, corbeille, correspondance des morceaux du village), `BuildAccessTest` (la règle « qui construit où »).
- En jeu : `StructuresWorkshopGameTest` (menu « Mondes », disposition, captures `valcombe-atelier-structures-*.png`, un bloc du puits changé, enregistré à l'écran, vu dans un village généré, retour à l'originale, nouvelle structure).
