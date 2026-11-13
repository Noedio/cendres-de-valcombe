# API d'administration du serveur

Contrat de l'API que le mod Valcombe ouvre sur un serveur dédié, pour que le launcher (ou tout autre outil) pilote le serveur : état, joueurs, console en direct, commandes, réglages, arrêt et redémarrage. Ce document fait foi : le launcher est écrit à partir de lui, et toute évolution du mod doit y être reportée.

Version du contrat : **1** (champ `api` de `GET /admin/etat`). Elle n'augmente que si un point d'accès change de façon incompatible ; un champ ajouté dans une réponse ne change pas la version, le client ignore ce qu'il ne connaît pas.

## En bref

| Méthode | Chemin | Rôle |
| --- | --- | --- |
| GET | `/admin/etat` | Version, durée de fonctionnement, TPS, mémoire, joueurs, message d'accueil, mondes |
| GET | `/admin/joueurs` | Joueurs connectés : nom, uuid, opérateur, monde, ping |
| GET | `/admin/console?depuis=<n>` | Lignes récentes de la console, avec leur numéro de séquence |
| POST | `/admin/commande` | Exécute une commande comme la console et renvoie sa sortie |
| GET | `/admin/reglages` | Liste blanche, opérateurs, message d'accueil, joueurs max, port |
| POST | `/admin/reglages` | Change ces réglages ; dit si un redémarrage est nécessaire |
| POST | `/admin/arreter` | Arrêt propre du serveur |
| POST | `/admin/redemarrer` | Arrêt propre, avec le drapeau qui demande la relance |
| GET | `/admin/ateliers` | Invitations à construire dans les ateliers, et WorldEdit dans les parties d'essai |
| POST | `/admin/ateliers` | Ouvre ou ferme WorldEdit aux opérateurs dans les parties d'essai |
| POST | `/admin/ateliers/inviter` | Invite un joueur à construire dans un atelier (ou dans tous) |
| POST | `/admin/ateliers/retirer` | Retire une invitation (ou toutes celles d'un joueur) |
| GET | `/admin/parties` | Le tableau des parties : chaque partie, son état, où elle en est, ses joueurs et ses places |
| POST | `/admin/parties` | Crée une partie d'essai (son monde, sa partie, le village qui s'y pose) |
| POST | `/admin/parties/supprimer` | Supprime une partie d'essai et son monde |
| POST | `/admin/parties/suspendre` | Met une partie en suspens : ses joueurs au hub, elle reprendra où elle en est |
| POST | `/admin/parties/reprendre` | Reprend une partie en suspens : ses joueurs sont rappelés à leur place |
| GET | `/admin/sauvegardes` | Les sauvegardes, celle en cours, la prochaine, la restauration prévue |
| POST | `/admin/sauvegardes` | Une sauvegarde maintenant |
| GET | `/admin/sauvegardes/reglages` | Réglages des sauvegardes automatiques |
| POST | `/admin/sauvegardes/reglages` | Change ces réglages |
| POST | `/admin/sauvegardes/restaurer` | Prévoit la restauration d'une sauvegarde au prochain démarrage, et redémarre si demandé |
| DELETE | `/admin/sauvegardes/restaurer` | Annule la restauration prévue |
| GET | `/admin/sauvegardes/<nom>` | Télécharge l'archive |
| DELETE | `/admin/sauvegardes/<nom>` | Efface une sauvegarde |

## Où et quand

- L'API est servie par le **serveur web du mod**, celui de la tablette du Conteur et de l'Atelier, sur le port `tabletPort` de `config/valcombe-communaute.json` (25570 par défaut, `/vc tablette port <numéro>` pour le changer). Pas de port de plus à ouvrir.
- Sur un **serveur dédié**, elle s'ouvre d'office au démarrage et reste ouverte jusqu'à l'arrêt. Le journal l'annonce : `API d'administration prête sur le port 25570 (requêtes de cette machine seulement)`. Si le port est déjà pris, le serveur démarre quand même et le journal le dit (`API d'administration indisponible : …`).
- Dans une **partie solo** (serveur intégré au jeu), elle ne s'ouvre pas : même quand la tablette ou l'Atelier font tourner le serveur web, `/admin/...` répond `404` sans regarder la clé. Les tests en jeu l'ouvrent eux-mêmes.
- Le serveur web parle **HTTP, sans chiffrement**. C'est pourquoi, par défaut, l'API n'accepte que les requêtes de la machine elle-même (voir plus bas) : depuis un autre PC, on passe par un **tunnel SSH**, qui chiffre tout.

## La clé d'administration

Chaque requête porte la clé dans l'en-tête :

```
X-Valcombe-Admin: 3f9c…(64 caractères)
```

- **Où elle est** : `config/valcombe/admin.json`, dans le dossier du serveur. Elle est tirée au hasard (32 octets, `SecureRandom`, écrits en 64 chiffres hexadécimaux) au premier démarrage du serveur avec ce mod, puis gardée. Sur Linux, le fichier n'est lisible que par le compte du serveur.
- **La voir** : elle s'affiche dans la console au premier démarrage. Ensuite : `/vc admin cle`, depuis la console du serveur ou en jeu pour un opérateur de niveau 4 (le niveau par défaut des opérateurs, celui de `/stop`). En jeu, elle ne s'affiche qu'à celui qui la demande, et un clic la copie.
- **La changer** : `/vc admin nouvellecle`. L'ancienne cesse aussitôt de fonctionner, et les blocages dus aux essais ratés sont levés.
- **Le launcher sur le même PC** peut lire `config/valcombe/admin.json` directement (champ `key`) plutôt que de la demander.
- `/vc admin` dit si l'API est ouverte, sur quel port, et pour quelles adresses.

### Le fichier `config/valcombe/admin.json`

```json
{
  "key": "3f9c…",
  "adminLocalOnly": true,
  "enabled": true,
  "created": "2026-11-13T09:12:44.512Z"
}
```

| Champ | Par défaut | Rôle |
| --- | --- | --- |
| `key` | tirée au hasard | La clé. Une clé écrite à la main est acceptée si elle fait 32 à 128 caractères parmi `A-Z a-z 0-9 _ -` ; sinon elle est remplacée par une neuve au démarrage. |
| `adminLocalOnly` | `true` | Seules les requêtes de la machine elle-même (127.0.0.1, ::1) sont acceptées. Un tunnel SSH arrive par là. Mettre `false` seulement derrière un autre moyen de protection (réseau privé, VPN). |
| `enabled` | `true` | `false` coupe l'API (pris en compte au prochain démarrage). |
| `created` | | Moment où la clé a été tirée, pour information. |

Un champ absent prend sa valeur par défaut. Un fichier illisible est renommé `admin.json.illisible` et remplacé par un neuf (nouvelle clé). Les changements faits à la main dans ce fichier sont lus au démarrage suivant.

## Sécurité

- **Clé comparée en temps constant** : la durée de la comparaison ne dépend ni du contenu ni de la longueur de la clé essayée.
- **Essais ratés limités par adresse** : cinq mauvaises clés en dix minutes bloquent l'adresse 30 secondes, puis 1, 2, 4, 8 minutes, jusqu'à 15 minutes par blocage. Pendant un blocage, toute requête de cette adresse reçoit `429` avec l'en-tête `Retry-After` (en secondes), même avec la bonne clé. Une bonne clé efface l'ardoise. Une requête **sans** clé n'est pas comptée.
- **Machine elle-même seulement** par défaut (`adminLocalOnly`) : une requête venue d'ailleurs reçoit `403`, même avec la bonne clé.
- **Corps de requête bornés** : 64 Ko au plus (`413` au-delà). Le JSON est lu strictement : un seul objet, rien après, pas de commentaire, pas de guillemets simples, UTF-8 valide (`400` sinon).
- **Fil du serveur** : tout ce qui lit ou change l'état du jeu s'exécute sur le fil du serveur Minecraft, avec un délai maximal (5 s pour une lecture, 30 s pour une commande ou des réglages). Un serveur trop occupé répond `504`, un serveur qui démarre ou s'arrête `503`.
- **Pas d'en-tête CORS** : une page web ouverte dans un navigateur ne peut pas appeler l'API (l'en-tête de clé déclenche une vérification préalable que le serveur ne valide pas).
- **Journal** : chaque commande, changement de réglage, arrêt ou redémarrage est écrit dans le journal du serveur avec l'adresse d'origine ; chaque clé refusée aussi.

## Accès depuis un autre PC : le tunnel SSH

Le launcher (ou vous) ouvre un tunnel vers le serveur, puis parle à `localhost` :

```bash
ssh -N -L 25570:localhost:25570 utilisateur@adresse-du-vps
```

Les requêtes vers `http://localhost:25570/admin/...` sur votre PC arrivent alors au serveur comme si elles venaient de la machine elle-même. Le port 25570 n'a pas besoin d'être ouvert au public pour cela.

Le launcher ouvre ce tunnel lui-même pour un serveur loué (`launcher/Services/Ssh`, `VpsServer.OpenAdminTunnelAsync`) : côté PC, un port libre choisi par Windows (jamais 25565, 25570 ni 24454, que le serveur personnel de l'hôte peut occuper) ; côté serveur, `127.0.0.1` et le port `tabletPort` lu dans `config/valcombe-communaute.json`. Il lit aussi la clé dans `config/valcombe/admin.json` par SSH (avec le compte du serveur, jamais en root) : l'hôte n'a rien à copier. Le tunnel ouvert (`IAdminTunnel` : adresse locale et clé) est enveloppé par `SshConnection`, une `IServerConnection` comme l'accès direct : la page Serveur s'en sert sans rien savoir de SSH.

## Forme des réponses

- Toujours du JSON en UTF-8 (`Content-Type: application/json; charset=utf-8`), avec `Cache-Control: no-store`, sauf le téléchargement d'une sauvegarde (`application/zip`).
- Les noms de champs sont en français, comme dans le reste du mod.
- En cas d'erreur, l'objet a un seul champ `erreur`, un message en français à montrer tel quel :

```json
{"erreur": "Clé d'administration refusée."}
```

| Statut | Quand |
| --- | --- |
| 200 | Réussi |
| 202 | Arrêt ou redémarrage accepté (le serveur s'arrête juste après la réponse) ; sauvegarde lancée (elle continue après la réponse) |
| 400 | Requête fautive : JSON invalide, champ inconnu, valeur hors bornes, paramètre illisible |
| 401 | Clé absente ou fausse |
| 403 | Adresse refusée (`adminLocalOnly`) |
| 404 | Point d'accès inconnu, API désactivée (`enabled: false`) ou fermée (partie solo) |
| 405 | Mauvaise méthode ; l'en-tête `Allow` dit laquelle est attendue |
| 409 | Impossible dans cet état (par exemple arrêter une partie solo, ou lancer une sauvegarde pendant qu'une autre se fait) |
| 413 | Corps de requête trop gros |
| 429 | Trop d'essais ratés : réessayer après `Retry-After` secondes |
| 500 | Erreur interne (détail dans le journal du serveur) |
| 503 | Le serveur démarre ou s'arrête |
| 504 | Le serveur n'a pas répondu à temps |

## Les points d'accès

### GET /admin/etat

```json
{
  "api": 1,
  "version": "0.14.0",
  "minecraft": "26.3",
  "dedie": true,
  "demarreLe": "2026-11-13T09:12:44.512Z",
  "dureeSecondes": 5321,
  "tps": 20.0,
  "tpsCible": 20.0,
  "msParTick": 3.42,
  "enVeille": false,
  "memoire": {"utiliseeMo": 1210, "reserveeMo": 2048, "maxMo": 4096},
  "joueurs": {"connectes": 3, "max": 20},
  "messageAccueil": "Les Cendres de Valcombe",
  "prochaineSoiree": "vendredi 21 h",
  "motd": "Prochaine soirée : vendredi 21 h\nSalon ouvert",
  "mondes": [],
  "parties": [],
  "redemarrageNecessaire": false
}
```

- `version` : version du mod Valcombe. `minecraft` : version du jeu.
- `dedie` : serveur dédié (vrai) ou partie solo (faux).
- `dureeSecondes` : depuis le démarrage du serveur.
- `tps` : ticks par seconde, moyenne des 100 derniers ticks, plafonnée à `tpsCible` (20 en temps normal). En dessous de 20, le serveur peine. `msParTick` : temps moyen d'un tick (un tick a 50 ms pour lui à 20 TPS).
- `enVeille` : un serveur dédié vide depuis une minute se met en veille et ne fait plus de tick ; `tps` reste alors la dernière valeur mesurée.
- `memoire` : mémoire Java utilisée, réservée et maximale (réglée par `-Xmx`), en Mo.
- `messageAccueil` : la première ligne de la description du serveur quand aucune soirée n'est annoncée. `motd` : la description telle que la liste des serveurs l'affiche (deux lignes, séparées par `\n`).
- `mondes` : les mondes de Valcombe inscrits dans `config/valcombe/mondes.json` (hub d'abord), donnés par le gestionnaire de mondes. Le monde principal de Minecraft n'y figure pas. Liste vide si aucun monde n'est inscrit ou si le gestionnaire n'a pas pu démarrer. Chaque monde a la forme :

```json
{"id": "partie-principale", "nom": "Partie principale", "sorte": "partie", "etat": "charge", "joueurs": 6}
```

  `sorte` : `hub`, `partie`, `essai`, `atelier-carte`, `atelier-structures`. `etat` : `charge` (ouvert), `remise-a-neuf` (recréation ou suppression en cours), `decharge` (fermé, il s'ouvrira à la demande). `joueurs` : joueurs présents dans ce monde. Un atelier a en plus `invites` : les joueurs invités à y construire (invitations à cet atelier et à tous les ateliers), en plus des opérateurs, par exemple `"invites": ["Alice", "Bob"]`. Un monde de partie (`partie`, `essai`) a en plus `partie` : l'état de sa partie, `{"statut": "en-cours", "etat": "nuit 2"}` (`statut` comme dans `parties`, `etat` en clair) ; le détail est dans `parties`. D'autres champs pourront s'ajouter.
- `parties` : les parties du serveur, une par monde de partie (la partie principale d'abord, puis les parties d'essai). Elles se jouent en même temps, séparées : chacune a ses joueurs, son Conteur, ses minuteurs et sa sauvegarde. Liste vide si les mondes de Valcombe n'ont pas pu démarrer. Chaque partie a la forme :

```json
{"id": "partie-principale", "nom": "Partie principale", "sorte": "partie", "principale": true, "etat": "jour 2",
 "enCours": true, "enPause": false, "suspendue": false, "phase": "day", "inscrits": 7, "assis": 7, "conteur": "Alice",
 "joueurs": 8, "statut": "en-cours", "statutTexte": "En cours", "phaseTexte": "Jour 2", "numero": 2, "participants": 7,
 "places": 12, "spectateurs": 1}
```

  `id` : l'identifiant de son monde (le même que dans `mondes`). `sorte` : `partie` ou `essai`. `etat` : ce qui s'y passe, en clair (`salon ouvert`, `3 inscrit(s)`, `nuit 1`, `jour 2, en pause`, `en suspens (jour 2)`, `mise en place`). `enCours` : des joueurs y sont assis, en suspens compris (les sauvegardes automatiques attendent la fin des parties qui se jouent, pas celles qui sont en suspens). `enPause` : la partie est en pause (par le Conteur, ou parce qu'elle est en suspens). `suspendue` : la partie est en suspens (voir `/admin/parties/suspendre`). `phase` : `setup`, `ready`, `night`, `day`, `ended`, ou `null` sans partie lancée. `inscrits` : joueurs au salon. `assis` : joueurs de la partie lancée (joueurs fictifs compris). `conteur` : le Conteur, ou `null` (Conteur automatique). `joueurs` : joueurs présents dans son monde.

  La fiche du tableau des parties (le hub, l'écran « Parties » du menu J et le launcher la montrent) : `statut` : `ouverte` (pas de partie lancée, le salon est ouvert), `en-cours`, `en-pause` (par le Conteur) ou `suspendue` ; `statutTexte` : le même en clair (`Ouverte`, `En cours`, `En pause`, `En suspens`). `phaseTexte` : `Salon ouvert`, `Mise en place`, `Nuit 2`, `Jour 3`. `numero` : le numéro de la nuit ou du jour en cours (0 au salon). `participants` : les joueurs de la partie, assis si elle est lancée, sinon inscrits. `places` : les trônes de son village, jamais plus que les 16 joueurs qu'accepte une partie (16 tant qu'elle n'a pas de village). `spectateurs` : ceux qui la regardent depuis le tableau des parties du hub.
- `redemarrageNecessaire` : un réglage enregistré n'agira qu'au prochain démarrage (voir `/admin/reglages`).

### GET /admin/joueurs

```json
{
  "connectes": 2,
  "max": 20,
  "joueurs": [
    {"nom": "Alice", "uuid": "0f2b…", "operateur": true, "monde": "minecraft:overworld", "ping": 38, "modeDeJeu": "adventure"},
    {"nom": "Bob", "uuid": "77c1…", "operateur": false, "monde": "minecraft:overworld", "ping": 112, "modeDeJeu": "adventure"}
  ]
}
```

Triés par nom. `ping` en millisecondes (-1 s'il n'est pas encore connu). `monde` : identifiant du monde Minecraft où se trouve le joueur.

### GET /admin/console

Les dernières lignes de la console du serveur (tout ce que le journal affiche, à partir du chargement du mod), gardées dans un tampon circulaire de 1 000 lignes. Chaque ligne a un **numéro de séquence** `n` qui ne fait que croître ; le client redemande « ce qui suit le dernier numéro vu ».

Paramètres :

| Paramètre | Rôle |
| --- | --- |
| *(aucun)* | Première lecture : les `max` dernières lignes (200 par défaut). |
| `depuis=<n>` | Les lignes de numéro strictement supérieur à `n`, de la plus ancienne à la plus récente (500 au plus par défaut). |
| `max=<m>` | Nombre de lignes au plus, de 1 à 1 000. |

```json
{
  "instance": "5b8e0c7a-…",
  "lignes": [
    {"n": 1041, "t": 1763025164512, "niveau": "INFO", "fil": "Server thread", "source": "net.minecraft.server.MinecraftServer", "texte": "Alice joined the game"},
    {"n": 1042, "t": 1763025170003, "niveau": "WARN", "fil": "Server thread", "source": "valcombe", "texte": "…"}
  ],
  "suivant": 1042,
  "premiere": 43,
  "perdues": 0,
  "plusLoin": false
}
```

- `t` : moment, en millisecondes depuis 1970 (UTC). `niveau` : `INFO`, `WARN`, `ERROR`… `texte` peut tenir sur plusieurs lignes (une trace d'erreur suit son message, 40 lignes au plus) ; une ligne est coupée à 4 000 caractères.
- `suivant` : à redonner comme `depuis` à la lecture suivante.
- `premiere` : plus ancien numéro encore gardé.
- `perdues` : lignes écrasées avant d'avoir été lues (le client a lu trop rarement).
- `plusLoin` : il reste des lignes au-delà de cette page ; relire tout de suite avec `depuis=suivant`.
- `instance` : change à chaque démarrage du serveur, et les numéros repartent alors de 1. Si `instance` a changé, le client oublie son dernier numéro. (Un `depuis` plus grand que le dernier numéro est de toute façon traité comme une première lecture du tampon entier.)

Suivi en direct : une lecture par seconde avec `depuis=<suivant>` suffit.

### POST /admin/commande

Exécute une commande **comme la console du serveur**, avec tous les droits : commandes de Minecraft (`list`, `say`, `whitelist`, `op`, `save-all`, `stop`…) et de Valcombe (`vc …`).

Requête :

```json
{"commande": "say La partie commence dans cinq minutes"}
```

- Une seule commande, sur une ligne, 2 000 caractères au plus ; la barre oblique du début est facultative.
- Seul le champ `commande` est accepté.

Réponse (200, que la commande ait réussi ou non) :

```json
{"ok": true, "resultat": 1, "sortie": ["[Server] La partie commence dans cinq minutes"]}
```

- `ok` : la commande a réussi. Une commande inconnue ou fautive donne `ok: false` et son message d'erreur dans `sortie`.
- `resultat` : la valeur renvoyée par la commande (nombre de joueurs touchés, etc.).
- `sortie` : ce que la commande a répondu (200 lignes au plus). C'est aussi écrit dans le journal, préfixé de `[admin]`, et donc visible dans `/admin/console`. Comme pour la console, les opérateurs en jeu sont prévenus si `broadcast-console-to-ops` est actif.
- Une commande longue (plus de 30 s) renvoie `504`, mais continue sur le serveur.
- `stop` arrête le serveur comme `/admin/arreter`, mais sans réponse garantie : préférez `/admin/arreter`.

### GET /admin/reglages

```json
{
  "listeBlanche": {"active": true, "imposee": true, "joueurs": ["Alice", "Bob"]},
  "operateurs": [{"nom": "Alice", "uuid": "0f2b…", "niveau": 4}],
  "messageAccueil": "",
  "messageAccueilParDefaut": "Les Cendres de Valcombe",
  "prochaineSoiree": "vendredi 21 h",
  "joueursMax": 20,
  "port": 25565,
  "portEnregistre": 25565,
  "modifiables": ["listeBlanche", "operateurs", "messageAccueil", "prochaineSoiree", "joueursMax", "port"],
  "redemarrageNecessaire": false,
  "enAttente": []
}
```

- `listeBlanche.active` : la liste blanche est en service (`white-list`). `imposee` : les joueurs retirés de la liste sont aussitôt renvoyés (`enforce-whitelist`).
- `messageAccueil` : vide quand c'est le message par défaut (`messageAccueilParDefaut`). Il s'affiche en première ligne dans la liste des serveurs de Minecraft quand aucune soirée n'est annoncée ; la seconde ligne dit l'état du salon ou de la partie.
- `port` : port Minecraft en service. `portEnregistre` : celui de `server.properties`, qui servira au prochain démarrage.
- `modifiables` : ce qu'on peut changer sur ce serveur (`joueursMax` et `port` seulement sur un serveur dédié).
- `redemarrageNecessaire` et `enAttente` : réglages enregistrés qui n'agiront qu'au prochain démarrage (aujourd'hui, seul `port` peut l'être).

### POST /admin/reglages

Tous les champs sont facultatifs, mais il en faut au moins un :

```json
{
  "listeBlanche": {"active": true, "ajouter": ["Alice", "Bob"], "retirer": ["Eve"]},
  "operateurs": {"ajouter": ["Alice"], "retirer": ["Mallory"]},
  "messageAccueil": "Bienvenue au bourg",
  "prochaineSoiree": "vendredi 21 h",
  "joueursMax": 16,
  "port": 25565
}
```

| Champ | Règle | Effet |
| --- | --- | --- |
| `listeBlanche.active` | `true` ou `false` | Immédiat. En l'activant, les joueurs connectés qui n'y sont pas sont renvoyés (comme `whitelist on`). |
| `listeBlanche.ajouter`, `retirer` | 50 noms au plus ; un nom : lettres, chiffres et `_`, 16 caractères au plus | Immédiat. Un joueur retiré est renvoyé si la liste est en service. |
| `operateurs.ajouter`, `retirer` | Mêmes noms | Immédiat ; niveau d'opérateur par défaut du serveur. |
| `messageAccueil` | Une ligne, 60 caractères au plus, sans `§` ; `""` revient au message par défaut | Immédiat (gardé dans `config/valcombe-communaute.json`). |
| `prochaineSoiree` | Même règle ; `""` l'efface | Immédiat, comme `/vc soiree`. |
| `joueursMax` | Entier de 1 à 1 000 | Immédiat et écrit dans `server.properties` (serveur dédié seulement). |
| `port` | Entier de 1 024 à 65 535, différent du port de la tablette | Écrit dans `server.properties`, **pris en compte au prochain redémarrage** (serveur dédié seulement). |

- Un même nom ne peut pas être à la fois ajouté et retiré. La casse ne compte pas (Alice = alice).
- Un nom inconnu du serveur est cherché auprès de Mojang (comme `whitelist add`) ; un joueur connecté est reconnu tout de suite.
- **Validation d'abord** : une demande qui contient la moindre faute (champ inconnu, valeur hors bornes, nom invalide) est refusée en entier (`400`), avec toutes ses fautes dans `erreur`, et rien n'est changé.
- **Puis application point par point** : une demande valide est appliquée ; un point qui échoue (compte Minecraft introuvable, réglage réservé au serveur dédié) n'empêche pas les autres. La réponse détaille chaque point :

```json
{
  "ok": false,
  "resultats": [
    {"reglage": "listeBlanche.ajouter", "valeur": "Alice", "ok": true, "message": "« Alice » ajouté à la liste blanche."},
    {"reglage": "listeBlanche.ajouter", "valeur": "Zzqx", "ok": false, "message": "Joueur inconnu : aucun compte Minecraft ne s'appelle « Zzqx »."},
    {"reglage": "port", "valeur": 25600, "ok": true, "message": "Port enregistré : 25600. Il sera pris en compte au prochain redémarrage du serveur."}
  ],
  "redemarrageNecessaire": true,
  "reglages": { "…": "les réglages à jour, comme GET /admin/reglages" }
}
```

  `ok` est vrai si tous les points ont réussi. Ajouter un joueur déjà présent, ou retirer un joueur absent, réussit avec un message qui le dit.

- Une demande qui dépasse 30 s (serveur très occupé, Mojang lent à trouver un nom) renvoie `504`, mais elle reste prévue sur le serveur et s'appliquera : relire `GET /admin/reglages` avant de la renvoyer.

### POST /admin/arreter

Arrêt propre, comme `/stop` : les mondes et la partie en cours sont enregistrés (la partie reprendra au prochain démarrage). Corps facultatif (`{}`), ignoré. Serveur dédié seulement (`409` sinon).

```json
{"ok": true, "message": "Arrêt du serveur en cours.", "drapeau": null}
```

La réponse (`202`) part avant l'arrêt ; ensuite le serveur web s'arrête avec le serveur. Le processus Java se termine : celui qui l'a lancé ne le relance pas.

### POST /admin/redemarrer

Comme `/admin/arreter`, mais le mod pose d'abord le **drapeau de redémarrage** : le fichier `config/valcombe/redemarrer` dans le dossier du serveur.

```json
{"ok": true, "message": "Redémarrage : le serveur s'arrête proprement, puis celui qui l'a lancé le relance.", "drapeau": "config/valcombe/redemarrer"}
```

Le mod ne peut pas se relancer lui-même : c'est **celui qui a lancé le serveur** qui le relance.

- **Règle** : quand le processus du serveur se termine, regarder si `config/valcombe/redemarrer` existe. S'il existe, l'effacer et relancer le serveur. Sinon, l'arrêt était voulu : ne rien relancer.
- Le contenu du fichier (`{"demandeLe": "…", "motif": "…"}`) n'est qu'informatif ; seule compte sa présence.
- Le mod efface aussi le drapeau à chaque démarrage, pour qu'un drapeau oublié ne relance pas un serveur qu'on arrêtera plus tard pour de bon.
- Si le drapeau ne peut pas être écrit, rien n'est arrêté et la réponse est `500`.
- **Qui le lit** :
  - `demarrer.sh` et `demarrer.bat`, fournis avec le serveur (`tools/make_modpack.py`), relancent le serveur tant que le drapeau est posé. Le service systemd `valcombe.service` lance `demarrer.sh` : un redémarrage par l'API ne passe donc pas par systemd.
  - Le launcher, quand il lance lui-même le serveur (page Héberger ou Serveur), applique la même règle (`LocalServer`) : il relance le serveur avec les mêmes réglages. Un arrêt qu'il a lui-même demandé ne relance jamais rien, même si un drapeau traîne.
  - Un serveur lancé autrement (commande `java` à la main, hébergeur avec son propre panneau) s'arrête simplement : il faut le relancer soi-même.

## Les parties : le tableau, la création, la suppression

Plusieurs parties se jouent en même temps, séparées, chacune dans son monde : la **partie principale** (créée au premier démarrage, elle ne se supprime pas) et autant de **parties d'essai** qu'on veut. Au hub, le **tableau des parties** les montre toutes ; un joueur y choisit la sienne, la rejoint (inscription au salon d'une partie ouverte) ou la regarde en spectateur (mode spectateur de Minecraft, sans voir les rôles ni entendre le vocal des joueurs). En jeu : menu J, « Parties » (au hub), ou `/vc partie liste|voir|rejoindre|regarder|sortir|aller|creer|supprimer`. Le launcher les montre dans l'onglet **Parties** de sa page Serveur. Les vignettes (la carte que porte une partie, les cartes et les structures) ne passent pas par cette API : le jeu les reçoit par ses paquets, l'Atelier web par sa propre API (voir [UI-HD.md](UI-HD.md), paragraphe « Vignettes, côté serveur »).

### GET /admin/parties

```json
{
  "parties": [
    {"id": "partie-principale", "nom": "Partie principale", "sorte": "partie", "principale": true, "etat": "nuit 2",
     "enCours": true, "enPause": false, "suspendue": false, "phase": "night", "inscrits": 8, "assis": 8,
     "conteur": "Alice", "joueurs": 9, "statut": "en-cours", "statutTexte": "En cours", "phaseTexte": "Nuit 2",
     "numero": 2, "participants": 8, "places": 12, "spectateurs": 1},
    {"id": "essai-du-mardi", "nom": "Essai du mardi", "sorte": "essai", "principale": false, "etat": "2 inscrit(s)",
     "enCours": false, "enPause": false, "suspendue": false, "phase": null, "inscrits": 2, "assis": 0,
     "conteur": null, "joueurs": 2, "statut": "ouverte", "statutTexte": "Ouverte", "phaseTexte": "Salon ouvert",
     "numero": 0, "participants": 2, "places": 16, "spectateurs": 0}
  ],
  "enCours": 1,
  "principale": "partie-principale",
  "disponible": true
}
```

- `parties` : les parties, la principale d'abord, de la forme décrite dans `/admin/etat`.
- `enCours` : combien se jouent (en suspens comprises). `principale` : l'identifiant de la partie principale.
- `disponible` : faux si les mondes de Valcombe n'ont pas pu démarrer (la liste est alors vide, et rien ne se crée).

### POST /admin/parties

Crée une partie d'essai : un monde plat neuf, sa partie, et le village de Valcombe qui s'y pose par petits lots (une à deux minutes), comme `/vc partie creer <nom>`. Corps : `{"nom": "Essai du mardi"}` (40 caractères au plus ; l'identifiant est tiré du nom). Réponse :

```json
{"partie": {"id": "essai-du-mardi", "nom": "Essai du mardi", "sorte": "essai", "statut": "ouverte", "...": "..."},
 "message": "Partie d'essai « Essai du mardi » créée (essai-du-mardi). Son village est en route. ..."}
```

`400` sans `nom` ou avec un nom trop long ; `409` si le monde ne peut pas être créé (le message dit pourquoi).

### POST /admin/parties/supprimer

Corps : `{"partie": "essai-du-mardi"}`. La partie d'essai et son monde disparaissent ; ses joueurs vont au hub. La suppression du monde se termine juste après la réponse (la partie n'est déjà plus dans `parties`). Réponse : `{"ok": true, "message": "Partie « Essai du mardi » supprimée : ses joueurs vont au hub, son monde est effacé."}`. `404` si aucune partie n'a ce monde ; `409` pour la partie principale, une partie lancée, en suspens comprise (l'arrêter d'abord : `/vc arreter` dans son monde), ou un monde déjà en cours de suppression.

## Les parties : mise en suspens et reprise

Une partie en cours peut être **mise en suspens** : son horloge est gelée (minuteurs, étapes de la nuit, aiguille du vote, joueurs fictifs s'arrêtent là où ils en sont), elle est enregistrée, et tous les joueurs de son monde vont au hub, leur position gardée. Elle reste ainsi, même après un redémarrage du serveur, jusqu'à ce qu'on la **reprenne** : ses joueurs reçoivent un lien `[Y aller]` et retrouvent leur siège en arrivant ; elle reste en pause le temps que tout le monde soit revenu (une minute au plus), puis repart d'elle-même. Les autres parties ne voient rien de tout cela. En jeu, c'est `/vc partie suspendre [id]` et `/vc partie reprendre [id]` (le Conteur de la partie ou un opérateur), le pupitre du Conteur (onglet Partie, « Mettre en suspens », puis « Reprendre la partie en suspens »), l'écran « Parties » du menu J, ou la fiche de la partie dans l'Atelier en jeu.

### POST /admin/parties/suspendre

Corps : `{"partie": "essai-du-mardi"}` (l'identifiant du monde de la partie, comme dans `parties`). Réponse : la partie après le geste (la forme de `parties`, voir `/admin/etat`), avec `message`, ce que le serveur en dit :

```json
{"id": "essai-du-mardi", "nom": "Essai du mardi", "sorte": "essai", "principale": false, "etat": "en suspens (jour 2)",
 "enCours": true, "enPause": true, "suspendue": true, "phase": "day", "inscrits": 6, "assis": 6, "conteur": "Alice",
 "joueurs": 0, "message": "Partie mise en suspens : horloge arrêtée, 7 joueur(s) renvoyé(s) au hub. Pour la reprendre : /vc partie reprendre."}
```

`404` si aucune partie n'a ce monde ; `409` si la partie n'est pas en cours, est finie ou est déjà en suspens (le message dit pourquoi) ; `400` sans `partie`.

### POST /admin/parties/reprendre

Même corps, même réponse. `409` si la partie n'est pas en suspens.

## Les ateliers : invitations à construire

On construit avec WorldEdit **dans les ateliers seulement** (atelier carte, atelier structures) : les opérateurs, et les joueurs qu'ils y invitent. Un invité voit l'atelier depuis le hub, y va, y construit en mode créatif avec WorldEdit (sans ce qui touche au serveur : recharger WorldEdit, changer de monde, scripts, fichiers). Les invitations sont gardées dans `config/valcombe/ateliers.json` ; en jeu : `/vc atelier inviter <joueur> [atelier|tous]`, `/vc atelier retirer <joueur> [atelier|tous]`, `/vc atelier liste`. Ce que l'API fait se voit en jeu tout de suite (menu « Mondes », droits de WorldEdit, retour au hub d'un joueur dont l'invitation est retirée), et inversement.

### GET /admin/ateliers

```json
{
  "worldEditEssais": false,
  "invitations": [
    {"joueur": "Alice", "uuid": "0f3c…", "atelier": "atelier-carte-1", "nomAtelier": "Carte du port", "par": "Yoann", "le": "2026-11-13T10:02:11Z"},
    {"joueur": "Bob", "uuid": null, "atelier": "*", "nomAtelier": "Tous les ateliers", "par": "l'API d'administration", "le": "2026-11-13T10:05:40Z"}
  ]
}
```

- `invitations` : dans l'ordre où elles ont été faites. `atelier` : l'identifiant de l'atelier, ou `*` pour tous les ateliers (y compris ceux créés plus tard). `uuid` : l'identifiant du compte, `null` tant que le joueur invité hors ligne n'est pas venu (il est reconnu par son nom, puis son identifiant est noté ; un joueur qui change de nom garde ses invitations). `par` : l'opérateur qui a invité, `la console` ou `l'API d'administration`.
- `worldEditEssais` : WorldEdit est-il ouvert aux opérateurs dans les parties d'essai (fermé par défaut ; jamais aux autres joueurs).

### POST /admin/ateliers

Corps : `{"worldEditEssais": true}` ou `false`. Réponse : comme `GET /admin/ateliers`. `400` sans ce champ (vrai ou faux).

### POST /admin/ateliers/inviter

Corps : `{"joueur": "Alice", "atelier": "atelier-carte-1"}`. `atelier` : identifiant ou nom d'un atelier (comme `/vc monde aller`), ou `tous`. Réponse : comme `GET /admin/ateliers`, avec `message` (la phrase de la commande en jeu : « Alice est invité à construire dans … »). Une invitation déjà là réussit avec un message qui le dit ; un opérateur n'a pas besoin d'invitation (message, rien d'enregistré). `409` si le nom n'est pas un nom de joueur Minecraft ou si l'atelier n'existe pas (le message dit lesquels existent) ; `400` sans `joueur` ou sans `atelier`.

### POST /admin/ateliers/retirer

Corps : `{"joueur": "Alice"}` retire toutes ses invitations ; `{"joueur": "Alice", "atelier": "atelier-carte-1"}` celle de cet atelier (`"tous"` : son invitation à tous les ateliers). Réponse : comme `GET /admin/ateliers`, avec `message`. `409` si le joueur n'a pas d'invitation à retirer. Un joueur connecté qui se trouve dans un atelier où il n'a plus droit revient au hub.

## Les sauvegardes

Le mod sauvegarde lui-même les mondes et les créations, dans des archives zip horodatées rangées dans le dossier `backups/` du serveur (à côté de `server.properties`), et les restaure d'un clic.

### Ce qu'une archive contient

| Dans l'archive | Ce que c'est |
| --- | --- |
| `monde/` | Le dossier du monde du serveur (`world/` d'ordinaire), donc **tous les mondes de Valcombe** : le hub, les parties, les ateliers sont rangés dans `world/dimensions/valcombe/<id>`. Le verrou `session.lock` n'y est pas. |
| `config/valcombe/` | Les créations de l'Atelier, les cartes de la bibliothèque, les structures, le registre des mondes (`mondes.json`), et la fiche de chaque partie (`parties/<monde>.json` : en cours, en pause ou en suspens, elle reprend au démarrage). |
| `config/valcombe-*` | L'état du jeu rangé à côté : fiche du village posé, instantané de la carte, statistiques, scripts. L'ancienne sauvegarde unique de partie (`valcombe-partie.json`, d'avant les parties multiples) est mise de côté au démarrage (`valcombe-partie.json.ancienne`). |
| `valcombe-sauvegarde.json` | La fiche de l'archive (dernière entrée) : sorte, date, auteur, version du mod et de Minecraft, nombre de fichiers, noms des mondes. Une archive sans fiche lisible n'est pas restaurée. |

**Jamais dans une archive, et jamais touchés par une restauration** :

- `config/valcombe/admin.json`, la **clé d'administration**. Une archive se télécharge et se copie sur d'autres machines : elle ne doit jamais porter un secret qui donne tout pouvoir sur le serveur. La chiffrer ne servirait à rien (la clé de chiffrement serait sur le même serveur), et restaurer une ancienne clé couperait le launcher qui pilote le serveur au moment même où il en a besoin.
- `config/valcombe-communaute.json` : le webhook Discord (un secret lui aussi), le port de la tablette et le message d'accueil, qui sont des réglages du serveur et non l'état du jeu.
- `config/valcombe/sauvegardes.json` (une restauration ne rallume ni n'éteint les sauvegardes automatiques), la demande de restauration, le drapeau de redémarrage, le lien d'ouverture de l'Atelier pour l'hôte et les fichiers en cours d'écriture (`.tmp`).
- Les réglages de Minecraft (`server.properties`, liste blanche, opérateurs) : restaurer les mondes ne change pas qui entre sur le serveur ni qui le commande.

### Nom d'une archive

`valcombe-AAAA-MM-JJ_HH-MM-SS-<sorte>.zip`, à l'heure du serveur, par exemple `valcombe-2026-11-13_09-12-44-auto.zip`. Deux archives de la même sorte nées dans la même seconde prennent un numéro (`...-manuelle-2.zip`). Le nom est l'identifiant de la sauvegarde partout (API, commandes) ; un nom qui ne suit pas exactement ce modèle est refusé.

| Sorte | Quand | Effacée par la rotation |
| --- | --- | --- |
| `auto` | À intervalle régulier | Oui |
| `arret` | À chaque arrêt du serveur | Oui |
| `manuelle` | Demandée (API, `/vc sauvegarde maintenant`) | Non : seulement à la main |
| `avant-restauration` | L'état du serveur juste avant une restauration, faite par la restauration elle-même | Oui |

**Rotation** : après chaque sauvegarde réussie, les plus anciennes des sortes `auto`, `arret` et `avant-restauration` sont effacées pour n'en garder que `garder` (10 par défaut). Les sauvegardes manuelles ne comptent pas et ne sont jamais effacées d'elles-mêmes. Un `garder` abaissé s'applique à la sauvegarde suivante, pas tout de suite.

### Comment une sauvegarde se fait

Une sauvegarde est **cohérente sans geler le jeu** :

1. sur le fil du serveur : la partie en cours est enregistrée, tout est écrit sur le disque (comme `save-all flush`), puis l'écriture des mondes est suspendue (comme `save-off`) : plus aucun fichier de monde ne change pendant la copie. Ce temps-là est court ;
2. sur un fil à part, de faible priorité : la copie dans l'archive, pendant que le jeu continue. L'archive s'écrit sous le nom `<nom>.zip.partiel` et ne prend son vrai nom qu'une fois complète ;
3. sur le fil du serveur : l'écriture des mondes reprend (comme `save-on`), chaque monde retrouvant exactement l'état qu'il avait (un `save-off` tapé par un opérateur est respecté). Puis la rotation.

Une seule sauvegarde à la fois. Avant de copier, le mod vérifie qu'il reste sur le disque la taille des fichiers plus 64 Mo ; sinon la sauvegarde échoue avec un message qui le dit. Si le serveur s'arrête pendant une copie, elle est abandonnée proprement (son `.partiel` est effacé) et la sauvegarde d'arrêt la remplace.

### Sauvegardes automatiques

Sur un **serveur dédié** seulement (en solo, seulement à la demande) :

- **à intervalle** : `intervalleMinutes` (6 heures par défaut) après la dernière sauvegarde, quelle qu'en soit la sorte (ou après le démarrage s'il n'y en a aucune). Le mod regarde toutes les 30 secondes, même quand le serveur vide est en veille ;
- **à l'arrêt** (`aLArret`) : une fois les mondes enregistrés et fermés, avant que le processus ne se termine. Elle n'est pas faite quand une restauration est prévue (la sauvegarde de sécurité du démarrage suivant contiendra le même état). Sur un gros monde, l'arrêt prend donc un peu plus de temps : le service systemd fourni laisse 10 minutes ;
- **jamais pendant une partie en cours**, par défaut (`pendantPartie: false`) : l'écriture des mondes sur le disque et la copie peuvent provoquer un à-coup, et on n'en veut pas au milieu d'un vote. Une sauvegarde automatique qui tombe pendant une partie (joueurs assis, de la mise en place à la fin) attend la fin de la partie, mais jamais plus de 24 heures. Les sauvegardes manuelles et d'arrêt se font toujours.

### GET /admin/sauvegardes

```json
{
  "dossier": "backups",
  "sauvegardes": [
    {
      "nom": "valcombe-2026-11-13_15-12-44-auto.zip",
      "sorte": "auto",
      "date": "2026-11-13T15:12:44+01:00",
      "octets": 48213301,
      "rotation": true,
      "lisible": true,
      "par": "automatique",
      "versionMod": "0.14.0",
      "fichiers": 1873,
      "octetsDecompresses": 61400211,
      "mondes": ["Hub", "Partie principale", "Atelier structures"]
    }
  ],
  "nombre": 1,
  "octetsTotal": 48213301,
  "espaceLibre": 81604378624,
  "enCours": null,
  "derniere": {"nom": "valcombe-2026-11-13_15-12-44-auto.zip", "sorte": "auto", "ok": true, "message": "Sauvegarde ... terminée : 46,0 Mo, 1873 fichiers, en 4 s.", "octets": 48213301, "fichiers": 1873, "dureeSecondes": 4.2, "fin": "2026-11-13T14:12:48.920Z", "effacees": []},
  "prochaineAuto": "2026-11-13T20:12:44Z",
  "reportee": false,
  "restaurationPossible": true,
  "restaurationPrevue": null,
  "derniereRestauration": null,
  "reglages": {"actives": true, "intervalleMinutes": 360, "aLArret": true, "garder": 10, "pendantPartie": false}
}
```

- `sauvegardes` : de la plus récente à la plus ancienne. `date` : moment de la sauvegarde, avec le décalage horaire du serveur. `octets` : taille de l'archive. `rotation` : la rotation peut l'effacer. `lisible: false` (avec `erreur`) : archive abîmée ou qui n'est pas une sauvegarde de Valcombe ; les champs tirés de la fiche (`par`, `versionMod`, `fichiers`, `octetsDecompresses`, `mondes`) manquent alors.
- `espaceLibre` : place libre sur le disque du dossier `backups/`, en octets (-1 si inconnue).
- `enCours` : la sauvegarde en cours, ou `null` :

```json
{"nom": "valcombe-2026-11-13_15-30-02-manuelle.zip", "sorte": "manuelle", "par": "API d'administration (127.0.0.1)", "debut": "2026-11-13T14:30:02.114Z", "phase": "copie", "fichiers": 812, "fichiersTotal": 1873, "octets": 30145872, "octetsTotal": 61400211, "pourcent": 49}
```

  `phase` : `attente` (elle commence au tick suivant), `enregistrement`, `copie`, `rotation`. Pour suivre une sauvegarde, relire toutes les secondes jusqu'à `enCours: null`, puis lire `derniere`.
- `derniere` : la dernière sauvegarde terminée depuis le démarrage, réussie ou non (`ok`, `message` à montrer tel quel), ou `null`. `effacees` : les anciennes effacées par la rotation.
- `prochaineAuto` : moment de la prochaine sauvegarde automatique (`null` si elles sont désactivées ou hors serveur dédié). `reportee` : elle attend la fin d'une partie en cours.
- `restaurationPossible` : faux en solo. `restaurationPrevue` : `{"nom", "demandeLe", "par"}` ou `null`. `derniereRestauration` : le compte rendu de la dernière restauration (voir plus bas), ou `null`.

### POST /admin/sauvegardes

Lance une sauvegarde **manuelle**. Corps vide ou `{}`. Réponse `202` tout de suite, la sauvegarde continue ensuite :

```json
{"ok": true, "message": "Sauvegarde valcombe-2026-11-13_15-30-02-manuelle.zip lancée : suivez-la dans GET /admin/sauvegardes (enCours).", "nom": "valcombe-2026-11-13_15-30-02-manuelle.zip", "enCours": {"…": "comme dans GET /admin/sauvegardes"}}
```

`409` si une sauvegarde est déjà en cours, ou si le serveur s'arrête.

### GET et POST /admin/sauvegardes/reglages

Les réglages sont gardés dans `config/valcombe/sauvegardes.json` (créé au premier démarrage).

```json
{
  "reglages": {"actives": true, "intervalleMinutes": 360, "aLArret": true, "garder": 10, "pendantPartie": false},
  "bornes": {"intervalleMinutes": [15, 10080], "garder": [1, 500]},
  "parDefaut": {"actives": true, "intervalleMinutes": 360, "aLArret": true, "garder": 10, "pendantPartie": false},
  "automatiquesPossibles": true,
  "prochaineAuto": "2026-11-13T20:12:44Z"
}
```

| Champ | Par défaut | Règle | Rôle |
| --- | --- | --- | --- |
| `actives` | `true` | `true` ou `false` | Sauvegardes automatiques (à intervalle et à l'arrêt) |
| `intervalleMinutes` | `360` | Entier de 15 à 10 080 (une semaine) | Délai après la dernière sauvegarde |
| `aLArret` | `true` | `true` ou `false` | Une sauvegarde à chaque arrêt |
| `garder` | `10` | Entier de 1 à 500 | Sauvegardes automatiques gardées |
| `pendantPartie` | `false` | `true` ou `false` | Faux : une sauvegarde automatique attend la fin de la partie en cours |

`POST` : un ou plusieurs de ces champs ; tout est validé d'abord, une demande fautive est refusée en entier (`400`, avec toutes ses fautes). Réponse `200` : `{"ok": true, "message": "...", ...}` suivi des mêmes champs que le `GET`. Pris en compte tout de suite. `automatiquesPossibles` : faux en solo, où les réglages sont gardés mais seules les sauvegardes manuelles se font.

### POST /admin/sauvegardes/restaurer

Un serveur qui tourne garde ses mondes ouverts : on ne remplace pas leurs fichiers sous ses pieds. La restauration est donc **prévue au prochain démarrage**.

```json
{"nom": "valcombe-2026-11-13_15-12-44-auto.zip", "redemarrer": true}
```

- `nom` (obligatoire) : la sauvegarde à restaurer. `redemarrer` (facultatif, `false` par défaut) : redémarrer tout de suite pour l'appliquer. **La restauration d'un clic, c'est `"redemarrer": true`.**
- Le mod vérifie l'archive (nom, existence, fiche lisible), puis pose la demande `config/valcombe/restaurer.json`. Une demande précédente est remplacée.
- Sans `redemarrer` : réponse `200`, la restauration attend le prochain démarrage (`POST /admin/redemarrer`, ou un arrêt et une relance à la main).
- Avec `redemarrer` : le mod pose aussi le **drapeau de redémarrage** et s'arrête proprement, exactement comme `POST /admin/redemarrer` (même règle pour celui qui a lancé le serveur). Réponse `202` :

```json
{"ok": true, "nom": "valcombe-2026-11-13_15-12-44-auto.zip", "redemarrage": true, "message": "Restauration de « ... » prévue au prochain démarrage du serveur. L'état actuel sera d'abord sauvegardé. Le serveur s'arrête proprement, puis celui qui l'a lancé le relance.", "drapeau": "config/valcombe/redemarrer"}
```

- Erreurs : `400` (nom absent ou hors modèle, champ inconnu), `404` (aucune sauvegarde de ce nom), `409` (partie solo, ou archive illisible), `500` (demande ou drapeau impossible à écrire ; si seul le drapeau a échoué, la restauration reste prévue et le serveur n'est pas arrêté).

**Au démarrage suivant**, le mod applique la demande **avant que Minecraft n'ouvre le monde** (il est initialisé avant même la lecture de `server.properties`). C'est pourquoi la restauration n'existe que sur un serveur dédié : en solo, le jeu ouvre le monde depuis son écran, avant que le mod puisse y toucher. Le déroulement est pensé pour ne jamais laisser le serveur à moitié restauré :

1. la demande est lue puis retirée tout de suite : quoi qu'il arrive, elle n'est pas rejouée (pas de boucle de redémarrages) ;
2. l'archive est déballée en entier dans `backups/.restauration` ; chaque fichier est contrôlé (somme de contrôle du zip, chemin sans danger, seulement ce qu'une sauvegarde peut contenir). Une archive abîmée s'arrête ici, **sans rien changer** ;
3. l'état actuel est sauvegardé (sorte `avant-restauration`) : une restauration se défait en restaurant cette archive-là. Si cette sauvegarde échoue (disque plein...), **rien n'est changé** ;
4. le monde actuel est mis de côté, le monde restauré prend sa place ; les fichiers de Valcombe de `config/` sont remplacés (ceux créés depuis la sauvegarde sont retirés ; les fichiers privés ne sont jamais touchés) ;
5. si cette mise en place échoue en route, l'état d'avant est remis depuis la sauvegarde de sécurité.

Le compte rendu est dans le journal du serveur (donc dans `/admin/console`) et dans `backups/derniere-restauration.json`, que `GET /admin/sauvegardes` donne dans `derniereRestauration` :

```json
{"nom": "valcombe-2026-11-13_15-12-44-auto.zip", "demandeLe": "2026-11-13T16:02:11.402Z", "faiteLe": "2026-11-13T16:02:19.977Z", "ok": true, "message": "Sauvegarde « valcombe-2026-11-13_15-12-44-auto.zip » restaurée. L'état d'avant est gardé dans « valcombe-2026-11-13_17-02-15-avant-restauration.zip ».", "securite": "valcombe-2026-11-13_17-02-15-avant-restauration.zip"}
```

Le launcher, après une restauration avec redémarrage, attend que l'API réponde de nouveau, puis lit `derniereRestauration` pour dire ce qui s'est passé.

### DELETE /admin/sauvegardes/restaurer

Annule la restauration prévue. Réponse `200` : `{"ok": true, "annulee": true, "message": "Restauration prévue annulée."}` (`annulee: false` s'il n'y en avait pas).

### GET /admin/sauvegardes/&lt;nom&gt;

Télécharge l'archive : `200`, `Content-Type: application/zip`, `Content-Disposition: attachment; filename="<nom>"`, `Content-Length` exact. `404` si elle n'existe pas (ou si le nom ne suit pas le modèle). Chaque téléchargement est noté dans le journal. Copiez de temps en temps une archive hors du serveur : une sauvegarde qui ne le quitte jamais ne protège pas d'une panne de ce serveur.

### DELETE /admin/sauvegardes/&lt;nom&gt;

Efface une sauvegarde (de n'importe quelle sorte). `200` : `{"ok": true, "message": "Sauvegarde « ... » effacée."}`, `404` si elle n'existe pas, `409` si sa restauration est prévue au prochain démarrage (annulez-la d'abord). La rotation, elle non plus, n'efface jamais l'archive dont la restauration est prévue.

### En jeu et à la console

| Commande | Qui | Rôle |
| --- | --- | --- |
| `/vc sauvegarde` | Opérateurs | Où en sont les sauvegardes (nombre, place, prochaine, en cours, dernière, restauration prévue) |
| `/vc sauvegarde maintenant` | Opérateurs | Une sauvegarde manuelle ; un message dit quand elle est finie |
| `/vc sauvegarde liste` | Opérateurs | Les 15 plus récentes ; un clic prépare la commande de restauration |
| `/vc sauvegarde reglages` | Opérateurs | Les réglages |
| `/vc sauvegarde restaurer <nom>` | Console, opérateurs de niveau 4 | Prévoit la restauration au prochain démarrage (sans redémarrer) |
| `/vc sauvegarde restaurer annuler` | Console, opérateurs de niveau 4 | Annule la restauration prévue |
| `/vc sauvegarde supprimer <nom>` | Console, opérateurs de niveau 4 | Efface une sauvegarde |
| `/vc sauvegarde reglage actives\|intervalle\|arret\|garder\|pendantpartie <valeur>` | Console, opérateurs de niveau 4 | Change un réglage (`oui`/`non`, ou un nombre) |

### Restaurer à la main

Serveur arrêté, une archive se restaure aussi sans le mod : remplacer le dossier du monde par le contenu de `monde/`, et les fichiers de Valcombe de `config/` par ceux de `config/` (sans toucher à `config/valcombe/admin.json`). Le plus simple reste de poser la demande puis de relancer le serveur.

## Exemples avec curl

```bash
CLE=$(python3 -c "import json;print(json.load(open('config/valcombe/admin.json'))['key'])")
curl -s -H "X-Valcombe-Admin: $CLE" http://localhost:25570/admin/etat
curl -s -H "X-Valcombe-Admin: $CLE" "http://localhost:25570/admin/console?depuis=0&max=50"
curl -s -H "X-Valcombe-Admin: $CLE" -H "Content-Type: application/json" \
     -d '{"commande": "list"}' http://localhost:25570/admin/commande
curl -s -H "X-Valcombe-Admin: $CLE" -H "Content-Type: application/json" \
     -d '{"listeBlanche": {"ajouter": ["Alice"]}}' http://localhost:25570/admin/reglages
curl -s -X POST -H "X-Valcombe-Admin: $CLE" http://localhost:25570/admin/redemarrer
curl -s -X POST -H "X-Valcombe-Admin: $CLE" http://localhost:25570/admin/sauvegardes
curl -s -H "X-Valcombe-Admin: $CLE" http://localhost:25570/admin/sauvegardes
curl -s -OJ -H "X-Valcombe-Admin: $CLE" http://localhost:25570/admin/sauvegardes/valcombe-2026-11-13_15-12-44-auto.zip
curl -s -H "X-Valcombe-Admin: $CLE" -H "Content-Type: application/json" \
     -d '{"intervalleMinutes": 180, "garder": 20}' http://localhost:25570/admin/sauvegardes/reglages
curl -s -H "X-Valcombe-Admin: $CLE" -H "Content-Type: application/json" \
     -d '{"nom": "valcombe-2026-11-13_15-12-44-auto.zip", "redemarrer": true}' http://localhost:25570/admin/sauvegardes/restaurer
curl -s -H "X-Valcombe-Admin: $CLE" http://localhost:25570/admin/parties
curl -s -H "X-Valcombe-Admin: $CLE" -H "Content-Type: application/json" \
     -d '{"nom": "Essai du mardi"}' http://localhost:25570/admin/parties
curl -s -H "X-Valcombe-Admin: $CLE" -H "Content-Type: application/json" \
     -d '{"partie": "essai-du-mardi"}' http://localhost:25570/admin/parties/supprimer
```

## Le client du launcher

La page **Serveur** du launcher est écrite contre ce contrat (`launcher/Services/Admin`) :

- `IServerConnection` : un chemin vers l'API. `DirectConnection` envoie les requêtes à une adresse (le serveur de ce PC sur `127.0.0.1`, ou un serveur joignable sans tunnel) ; `SshConnection` passe par un tunnel SSH vers un serveur loué (profil de `ValcombeData/serveurs-loues.json`, clé et port de l'API lus sur le serveur, tunnel rouvert s'il tombe, démarrage du serveur arrêté par systemd). `ServerConnections.Open` choisit d'après le champ `Access` du serveur enregistré (`direct` ou `ssh`, avec `VpsId` pour le profil SSH).
- `AdminClient` : un appel par point d'accès ; une erreur devient `AdminException`, avec le message `erreur` du serveur. Après un `401`, un `403` ou un `404`, la page cesse d'interroger le serveur jusqu'à « Réessayer » (insister avec une mauvaise clé mènerait au blocage) ; après un `429`, elle attend `Retry-After`.
- `ConsoleFeed` : le suivi de `/admin/console` (une lecture par seconde sur l'onglet Console, `depuis`, changement d'`instance`, `plusLoin`).
- Les sauvegardes (onglet Sauvegardes, `MainWindow.Backups.cs`) : `GET /admin/sauvegardes` toutes les cinq secondes sur l'onglet, chaque seconde pendant une sauvegarde (`enCours`), et quel que soit l'onglet pendant une restauration avec redémarrage, jusqu'à lire dans `derniereRestauration` le compte rendu de celle qu'il a demandée. Un `404` sur ces chemins (serveur plus ancien) est dit sur l'onglet sans arrêter la page. Le téléchargement passe par `IServerConnection.DownloadAsync` : lu au fil de l'eau dans un fichier `.partiel`, sans délai global mais interrompu après une minute sans données, taille comparée à `Content-Length`, puis l'archive est ouverte et sa fiche lue (`BackupArchive`) avant de prendre son nom. Un nom de sauvegarde hors du modèle n'est jamais envoyé.
- Les parties (onglet Parties, `MainWindow.Parties.cs`) : `GET /admin/parties` toutes les deux secondes sur l'onglet ; chaque partie avec son état, sa phase, la jauge de ses places, son Conteur, qui est dans son monde et qui la regarde. « Suspendre » et « Reprendre » (`/admin/parties/suspendre|reprendre`), la suppression d'une partie d'essai après une confirmation qui dit ce qui disparaît (`/admin/parties/supprimer`), et la création d'une partie d'essai (`POST /admin/parties`). Le message du serveur s'affiche en avis en haut de l'onglet. Un identifiant de partie hors du modèle (`[a-z0-9-]{2,30}`) et un nom de plus de 40 caractères ne partent jamais. Un `404` (serveur plus ancien) est dit sur l'onglet sans arrêter la page ; un serveur qui n'envoie pas encore `statut` voit l'état déduit de `enCours`, `enPause` et `suspendue`. La vue d'ensemble donne, pour chaque monde de partie, l'état de sa partie (`mondes[].partie`).
- Pour le serveur de ce PC, la clé est lue dans `config/valcombe/admin.json` et le port dans la ligne du journal « API d'administration prête sur le port … ». Une clé de serveur distant est gardée chiffrée avec DPAPI dans `launcher.json`.
- `ValcombeLauncher.exe --verifier-admin rapport.txt` essaie le client contre un serveur HTTP fictif qui suit ce contrat (`DemoAdminApi`, refus et sauvegardes compris) ; `--capture` montre la page avec ce même serveur fictif.

## Ce qui viendra

- Rien d'annoncé pour l'instant : toute évolution du contrat sera décrite ici avant d'être livrée.
