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

## Forme des réponses

- Toujours du JSON en UTF-8 (`Content-Type: application/json; charset=utf-8`), avec `Cache-Control: no-store`.
- Les noms de champs sont en français, comme dans le reste du mod.
- En cas d'erreur, l'objet a un seul champ `erreur`, un message en français à montrer tel quel :

```json
{"erreur": "Clé d'administration refusée."}
```

| Statut | Quand |
| --- | --- |
| 200 | Réussi |
| 202 | Arrêt ou redémarrage accepté (le serveur s'arrête juste après la réponse) |
| 400 | Requête fautive : JSON invalide, champ inconnu, valeur hors bornes, paramètre illisible |
| 401 | Clé absente ou fausse |
| 403 | Adresse refusée (`adminLocalOnly`) |
| 404 | Point d'accès inconnu, API désactivée (`enabled: false`) ou fermée (partie solo) |
| 405 | Mauvaise méthode ; l'en-tête `Allow` dit laquelle est attendue |
| 409 | Impossible dans cet état (par exemple arrêter une partie solo) |
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
  "version": "0.13.1",
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

  `sorte` : `hub`, `partie`, `essai`, `atelier-carte`, `atelier-structures`. `etat` : `charge` (ouvert), `remise-a-neuf` (recréation ou suppression en cours), `decharge` (fermé, il s'ouvrira à la demande). `joueurs` : joueurs présents dans ce monde. D'autres champs pourront s'ajouter (l'état des parties au sprint 12).
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
```

## Le client du launcher

La page **Serveur** du launcher est écrite contre ce contrat (`launcher/Services/Admin`) :

- `IServerConnection` : un chemin vers l'API. `DirectConnection` envoie les requêtes à une adresse (le serveur de ce PC sur `127.0.0.1`, ou un serveur joignable sans tunnel) ; le tunnel SSH implémentera la même interface. `ServerConnections.Open` choisit d'après le champ `Access` du serveur enregistré.
- `AdminClient` : un appel par point d'accès ; une erreur devient `AdminException`, avec le message `erreur` du serveur. Après un `401`, un `403` ou un `404`, la page cesse d'interroger le serveur jusqu'à « Réessayer » (insister avec une mauvaise clé mènerait au blocage) ; après un `429`, elle attend `Retry-After`.
- `ConsoleFeed` : le suivi de `/admin/console` (une lecture par seconde sur l'onglet Console, `depuis`, changement d'`instance`, `plusLoin`).
- Pour le serveur de ce PC, la clé est lue dans `config/valcombe/admin.json` et le port dans la ligne du journal « API d'administration prête sur le port … ». Une clé de serveur distant est gardée chiffrée avec DPAPI dans `launcher.json`.
- `ValcombeLauncher.exe --verifier-admin rapport.txt` essaie le client contre un serveur HTTP fictif qui suit ce contrat (`DemoAdminApi`, refus compris) ; `--capture` montre la page avec ce même serveur fictif.

## Ce qui viendra

- L'état des parties dans `mondes` (sprint 12).
- Les sauvegardes (sprint 14, ticket à part).
