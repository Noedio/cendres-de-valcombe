# Les Cendres de Valcombe — héberger le serveur

Deux façons d'accueillir vos amis :

- **Sur votre PC** : le plus simple pour commencer. Voir [INSTALLATION.md](INSTALLATION.md), partie « Pour l'hôte ».
- **Sur un petit serveur loué** (VPS Linux) : le serveur reste allumé sans votre PC. C'est l'objet de ce guide.

Pour le serveur loué, deux chemins : **depuis le launcher**, qui fait tout par SSH (partie suivante), ou **à la main**, étape par étape (parties 1 à 8). Les deux donnent le même serveur : même dossier `/opt/valcombe`, même compte `valcombe`, même service `valcombe.service`. On peut passer de l'un à l'autre.

## Depuis le launcher (SSH)

Le launcher se connecte au VPS par SSH et s'occupe du reste : il installe Java, le lanceur Fabric et les mods de la dernière version publiée, crée le compte et le service, puis démarre, arrête, redémarre et met à jour le serveur, et lit son journal. Pour le reste (joueurs, console, liste blanche, opérateurs), il passe par l'API d'administration du mod (partie 8), dans un tunnel SSH qu'il ouvre lui-même.

### Ce qu'il faut

- Un VPS **Linux avec systemd**, processeur **x86_64 ou ARM (aarch64)**. Prévu pour **Debian 12/13 et Ubuntu 24.04** ; les autres systèmes avec systemd devraient convenir, le launcher le signale. 2 cœurs et 4 Go de mémoire au minimum.
- Son **adresse**, et un **compte SSH** : `root`, ou un compte qui a `sudo` (`debian`, `ubuntu` sur les images des hébergeurs). Avec un mot de passe, ou mieux une **clé SSH** (formats OpenSSH, PEM ou PuTTY, avec ou sans phrase de passe).
- Si le compte se connecte par clé mais que `sudo` demande un mot de passe, indiquez aussi ce **mot de passe sudo**.

Les mots de passe, la clé privée et sa phrase de passe sont gardés **chiffrés** sur votre PC (DPAPI de Windows, comme la connexion Microsoft du launcher), dans `ValcombeData/serveurs-loues.json`. Ils ne se déchiffrent que dans votre session Windows. Le mot de passe sudo est passé à `sudo` par son entrée standard, jamais sur une ligne de commande.

### Pas à pas

Page **Serveur** du launcher, bouton **« Ajouter un serveur »**, puis **« Enregistrer un serveur loué »**. Un parcours guidé en six étapes, que l'on suit de haut en bas ; chaque attente (connexion, examen, installation) se fait sans figer le launcher, et « Arrêter » l'interrompt.

1. **Adresse** : un nom pour la fiche, l'adresse du serveur et le port SSH (22 d'habitude), tels que l'hébergeur les donne. Une adresse collée avec son compte et son port (`ssh://debian@vps.exemple.fr:2222`) est remise en ordre toute seule.
2. **Compte** : `root` ou le compte de l'hébergeur, puis le **mot de passe** ou la **clé privée** (bouton « Choisir... », qui ouvre `C:\Users\vous\.ssh`), avec sa phrase de passe s'il y en a une. La clé est lue et vérifiée tout de suite ; le launcher garde son texte (chiffré), pas son chemin. Si `sudo` demande un mot de passe à part, cochez la case et indiquez-le.
3. **Empreinte** : à la première connexion, le launcher s'arrête et montre l'empreinte de la clé du serveur (`SHA256:...`), en grand, avec un bouton pour la copier. Comparez-la à celle que donne votre hébergeur (panneau, e-mail de livraison, ou sur sa console de secours `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`), puis acceptez-la. Le launcher la retient (`ValcombeData/ssh-hotes-connus.json`) et **refuse ensuite net toute autre clé**, en montrant l'ancienne et la nouvelle : si le serveur a été réinstallé, « J'ai réinstallé ce serveur : oublier l'ancienne empreinte » (après une confirmation) fait redemander la nouvelle ; sinon, n'allez pas plus loin, quelqu'un se fait peut-être passer pour lui. Le mot de passe n'est essayé qu'après l'empreinte, comme le fait SSH.
4. **Système** : le launcher examine le serveur et l'affiche en liste cochée : Linux, processeur, distribution, systemd, droits d'administration (root, sudo sans ou avec mot de passe), mémoire, place libre, et Valcombe (absent, ou déjà là et à quelle version). Ce qui empêche l'installation est dit en rouge : un `sudo` refusé propose aussitôt d'indiquer le bon mot de passe sudo et de réessayer. Un Valcombe déjà installé et à jour passe directement au démarrage ; à une version plus ancienne, on choisit de le mettre à jour ou de le garder.
5. **Installation** : la mémoire donnée à Java (4 Go proposés, moins sur une petite machine, qui garde toujours 1 Go pour elle), le port du jeu (**25565**, le port standard : sur une machine louée, c'est le bon choix), celui du chat vocal (24454, UDP) et l'ouverture du pare-feu ; en « Options avancées », le dossier (`/opt/valcombe`), le compte du serveur (`valcombe`), le nom du service (`valcombe`) et le port de l'API d'administration (celui du mod, 25570, sauf choix contraire : il n'est jamais ouvert au public). Puis le **contrat de Minecraft** : lisez-le (lien dans la case, https://aka.ms/MinecraftEULA) et cochez son acceptation ; sans elle, le bouton « Installer » reste éteint. L'installation montre sa progression et son journal en direct. Le launcher envoie le script `install-valcombe.sh` et un plan d'installation par SFTP dans un dossier temporaire du VPS, puis le lance en root. Le script :
   - crée le compte système `valcombe` (sans mot de passe ni connexion possible) et lui confie le dossier `/opt/valcombe` ;
   - passe ensuite la main à ce compte (`runuser`) pour tout ce qui va dans ce dossier : root n'y écrit rien et n'y lance rien, pour qu'un serveur détourné ne puisse pas s'en servir pour gagner les droits de root ;
   - télécharge le **Java officiel de Mojang** pour Linux (le même que celui du jeu ; sur un VPS ARM, Mojang n'en publie pas et c'est le Java d'Adoptium, de la même version), le **lanceur du serveur Fabric** et les **mods du serveur** (Fabric API, Simple Voice Chat, WorldEdit, Valcombe) : chaque fichier est vérifié par son empreinte, et seuls des sites connus sont acceptés (Mojang, Fabric, GitHub, Modrinth) ;
   - écrit `server.properties` (monde plat, liste blanche active au premier lancement ; un fichier existant n'est que retouché), `eula.txt`, `demarrer.sh` et `sauvegarder.sh` ;
   - installe le service `valcombe.service` (compte dédié, protections de systemd), l'active au démarrage de la machine, sans le démarrer ;
   - ouvre le port du jeu (TCP) et celui du chat vocal (UDP) dans `ufw` ou `firewalld` **s'ils sont déjà actifs** (il n'active jamais un pare-feu : cela pourrait couper SSH). Chez certains hébergeurs, ouvrez aussi ces ports dans leur interface web.
   Ensuite, le launcher **démarre** le service et attend que Minecraft écoute (le premier démarrage télécharge Minecraft et crée le monde : une à trois minutes), puis que l'API d'administration réponde par le tunnel. La clé d'administration est tirée à ce moment-là ; le launcher la lit lui-même sur le serveur, il n'y a rien à copier. **La fiche est enregistrée** dès l'installation finie : un premier démarrage très long ne fait pas perdre le serveur.
6. **Joueurs** : votre pseudo comme **premier opérateur** (il est aussi mis sur la liste blanche, sinon il ne pourrait pas entrer), les pseudos de vos joueurs pour la **liste blanche** (séparés par des virgules, des espaces ou des retours à la ligne), et la liste blanche en service ou non. Le launcher les envoie par l'API (les pseudos inconnus du serveur sont cherchés chez Mojang) et montre le résultat point par point. Étape facultative : « Passer cette étape ».

À la fin, la fiche est dans la liste des serveurs de la page Serveur, avec l'adresse à donner aux joueurs (bouton « Copier »).

**Modifier une fiche** : choisissez le serveur dans la liste, puis le crayon. Le même parcours s'ouvre, prérempli ; un mot de passe, une clé ou une phrase de passe **laissés vides sont gardés**. Après la connexion, « Enregistrer » garde les changements, « Installer ou mettre à jour » repasse par l'installation. **Retirer ce serveur** (même écran) efface de ce PC la fiche, le compte SSH, ses secrets et l'empreinte retenue ; le serveur lui-même n'est pas touché.

### Ensuite

- **État et pilotage** : la page Serveur montre l'état (version, durée, TPS, mémoire, joueurs), les joueurs connectés, les mondes, la console en direct, les réglages et les sauvegardes, comme pour tout serveur ; tout passe par le tunnel SSH.
- **Mettre à jour** : quand une nouvelle version est publiée, le bouton « Mettre à jour (vX) » apparaît à côté de Redémarrer et d'Arrêter (le launcher compare la version installée sur le serveur à la dernière publiée). Il relance le même script : seul ce qui a changé est téléchargé, un mod qui n'est plus dans la version est retiré, les réglages changés entre-temps (port, joueurs max, liste blanche...) sont gardés. Si le serveur tournait et que quelque chose a changé, il est redémarré : la partie en cours est enregistrée et reprend.
- **Démarrer** : un serveur loué arrêté (ou en panne) se relance d'ici, bouton « Démarrer » : la page dit que le service est arrêté (ou en panne, avec où lire pourquoi), puis suit le démarrage jusqu'à ce que l'API réponde. **Arrêter et redémarrer** passent par l'API ; l'arrêt laisse jusqu'à deux minutes à Minecraft pour enregistrer les mondes.
- **Journal** : les dernières lignes du service, ou en direct ; utile surtout quand le serveur ne démarre pas (la console de l'API d'administration prend le relais ensuite).
- **API d'administration** : le launcher ouvre un tunnel SSH d'un port libre de votre PC (choisi par Windows, jamais 25565, 25570 ni 24454) vers l'API du serveur, et lui parle par là. Rien à ouvrir dans le pare-feu du VPS.

### Si ça ne marche pas

| Message | Que faire |
| --- | --- |
| « ne répond pas » | Adresse ou port SSH faux, serveur éteint, ou pare-feu de l'hébergeur qui bloque SSH. |
| « a refusé la connexion » | Mot de passe faux, connexion par mot de passe désactivée sur le serveur, ou clé publique absente de `~/.ssh/authorized_keys`. |
| « la clé a changé » | Serveur réinstallé : oubliez l'ancienne empreinte. Sinon, ne vous connectez pas. |
| « n'a pas les droits d'administration », « sudo refuse » | Connectez-vous en `root`, ou avec un compte qui a `sudo` (indiquez son mot de passe sudo à l'étape Système). |
| « Clé privée illisible ou phrase de passe incorrecte » | Choisissez la clé **privée** (pas le `.pub`) et vérifiez sa phrase de passe. |
| « La clé d'administration n'existe pas encore » | Le serveur n'a jamais démarré : démarrez-le une première fois (bouton Démarrer). |
| « sans API d'administration » | Le serveur tourne une version de Valcombe sans API : mettez-le à jour (crayon, puis « Installer ou mettre à jour »). |
| « Une autre installation est en cours » | Attendez la fin de l'autre (deux launchers sur le même serveur). |
| Le serveur ne démarre pas | Lisez le journal du service dans le launcher, ou `sudo journalctl -u valcombe -n 100` sur le VPS. |

### Vérifier sans VPS, ou essayer sur un VPS en ligne de commande

- `ValcombeLauncher.exe --verifier-vps rapport.txt` : contrôles internes, le **parcours d'enregistrement** joué de bout en bout contre un serveur loué fictif (empreinte inconnue, fausse, acceptée, changée et oubliée ; hôte injoignable, mot de passe faux, sudo refusé ; installation avec contrat exigé ; démarrage jusqu'à l'API ; opérateur et liste blanche ; fiche enregistrée, modifiée, retirée), le plan d'installation de la dernière version publiée, puis installation, mise à jour et pilotage du service **à blanc**, avec le vrai script, dans un dossier temporaire de votre PC (il faut Git pour Windows, dont le launcher emprunte `bash`). Rien n'est installé sur le PC, aucun port n'est ouvert. `--sans-telechargement` évite les 120 Mo de Java et des mods.
- `ValcombeLauncher.exe --essai-vps rapport.txt --hote adresse --utilisateur root --cle C:\chemin\id_ed25519` : essai sur un vrai VPS, en lecture seule (système, état du service, journal, et `GET /admin/etat` par le tunnel si le serveur tourne). Au premier contact, le rapport donne l'empreinte ; relancez avec `--empreinte SHA256:...` pour l'accepter. Ajoutez `--installer --contrat-accepte` pour installer ou mettre à jour, `--demarrer` pour démarrer. Mot de passe : `--mot-de-passe-env NOM` (lu dans la variable d'environnement `NOM`), phrase de passe : `--phrase-env NOM`.

## À la main

Les parties 1 à 8 décrivent l'installation sans le launcher, commande par commande.

## Ce qu'il faut

- Un VPS sous **Debian 12/13 ou Ubuntu 24.04**, avec **2 cœurs et 4 Go de mémoire** au minimum (8 Go au-delà de 12 joueurs). Les offres d'entrée de gamme des hébergeurs français suffisent.
- Un accès SSH (l'hébergeur vous donne l'adresse et l'identifiant).
- Sur votre PC : le dossier `minecraft/dist/serveur-valcombe/`, produit par `python tools/make_modpack.py`. Son dossier
  `mods/` contient tout ce que le serveur charge : Fabric API, Simple Voice Chat, WorldEdit (la construction dans les
  ateliers, réservée aux opérateurs) et le mod Valcombe. Rien d'autre à installer.

## 1. Installer Java 25

```bash
sudo apt update
sudo apt install -y wget apt-transport-https gpg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-25-jre
java -version
```

## 2. Créer un compte dédié et copier le serveur

```bash
sudo useradd --system --create-home --home-dir /opt/valcombe valcombe
```

Depuis votre PC, envoyez le contenu de `serveur-valcombe/` (WinSCP, FileZilla en SFTP, ou `scp`) :

```bash
scp -r dist/serveur-valcombe/* utilisateur@adresse-du-vps:/tmp/valcombe/
```

Puis, sur le VPS :

```bash
sudo cp -r /tmp/valcombe/* /opt/valcombe/
sudo chown -R valcombe:valcombe /opt/valcombe
sudo chmod +x /opt/valcombe/demarrer.sh /opt/valcombe/sauvegarder.sh
```

Téléchargez le lanceur de serveur Fabric (Minecraft 26.3, Loader 0.20.1) depuis https://fabricmc.net/use/server/, puis placez-le dans `/opt/valcombe/` sous le nom `fabric-server-launch.jar`.

## 3. Premier lancement et contrat Minecraft

```bash
sudo -u valcombe /opt/valcombe/demarrer.sh
```

Le serveur s'arrête et crée `eula.txt`. Lisez le contrat https://aka.ms/MinecraftEULA ; si vous l'acceptez, mettez `eula=true` dans `/opt/valcombe/eula.txt`.

## 4. Démarrage automatique

```bash
sudo cp /opt/valcombe/valcombe.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now valcombe
sudo systemctl status valcombe
```

- Voir ce qui se passe : `sudo journalctl -u valcombe -f`.
- Redémarrer après une mise à jour des mods : `sudo systemctl restart valcombe`.
- Plus de mémoire : `sudo systemctl edit valcombe`, puis ajoutez `Environment=VALCOMBE_RAM=6G` sous `[Service]`.

Pour taper des commandes de console (`whitelist add`, `op`), connectez-vous au jeu avec un compte opérateur, ou passez par l'API d'administration (partie 8). Vous pouvez aussi activer RCON dans `server.properties` et utiliser un client RCON, en gardant le port RCON fermé au public.

Premier opérateur : arrêtez le service, ajoutez votre pseudo dans `ops.json` et `whitelist.json` (ou lancez `demarrer.sh` à la main et tapez `op <pseudo>` puis `whitelist add <pseudo>`), puis relancez.

## 5. Ouvrir les ports

| Port | Protocole | Pour quoi | Obligatoire |
| --- | --- | --- | --- |
| 25565 | TCP | Minecraft | oui |
| 24454 | UDP | Simple Voice Chat | oui |
| 25570 | TCP | Grimoire sur tablette | seulement si le Conteur l'utilise hors du réseau local |

Avec le pare-feu `ufw` :

```bash
sudo ufw allow OpenSSH
sudo ufw allow 25565/tcp
sudo ufw allow 24454/udp
sudo ufw enable
```

Ouvrez 25570 seulement les soirs où la tablette sert, puis refermez-le (`sudo ufw allow 25570/tcp` et `sudo ufw delete allow 25570/tcp`). Chez certains hébergeurs, il faut aussi ouvrir les ports dans leur interface web. L'API d'administration (partie 8) n'a jamais besoin de ce port ouvert : elle passe par SSH.

## 6. Sauvegardes automatiques

**Le mod sauvegarde tout seul** les mondes (le hub, les parties, les ateliers) et les créations de l'Atelier, dans `/opt/valcombe/backups/` : toutes les 6 heures et à chaque arrêt, en gardant les 10 dernières ; celle qui tombe pendant une partie attend la fin de la partie (24 heures au plus). Le jeu continue pendant la copie. Rien à installer :

- `/vc sauvegarde` dit où elles en sont, `/vc sauvegarde maintenant` en fait une tout de suite, `/vc sauvegarde liste` les montre ;
- **restaurer** : `/vc sauvegarde restaurer <nom>` (console ou opérateur de niveau 4), puis `sudo systemctl restart valcombe`. La restauration se fait au démarrage, avant l'ouverture du monde ; l'état d'avant est d'abord sauvegardé, de quoi revenir en arrière. L'API d'administration le fait d'un clic (restauration puis redémarrage) ;
- les réglages (intervalle, nombre gardé...) : `/vc sauvegarde reglages`, ou `config/valcombe/sauvegardes.json`.

**Depuis le launcher**, page Serveur, onglet **Sauvegardes** (pour ce PC comme pour un serveur distant ou loué) :

- la liste des archives, de la plus récente à la plus ancienne : date (« aujourd'hui à 15:12 »), sorte (automatique, à l'arrêt, manuelle, avant restauration), taille, nombre de mondes ; une archive abîmée est signalée en rouge. En tête : le nombre d'archives, la place qu'elles prennent et celle qui reste sur le disque du serveur ;
- **« Sauvegarder maintenant »** : une sauvegarde manuelle, suivie phase par phase (enregistrement des mondes, copie avec son pourcentage, rangement), sans arrêter le jeu ; la page dit quand elle est finie ;
- **télécharger** une archive sur votre PC (flèche) : vous choisissez où l'enregistrer, elle arrive au fil de l'eau (rien en mémoire, même pour un gros monde), puis le launcher la **contrôle** (zip lisible, fiche de la sauvegarde, tous les fichiers annoncés) avant de lui donner son nom. Une archive coupée en route ne remplace jamais un fichier ;
- **restaurer** : une confirmation dit clairement ce qui sera **perdu** (tout ce qui a changé depuis cette sauvegarde, avec le temps écoulé), ce qui est **gardé à part** (l'état actuel, sauvegardé d'abord), le **redémarrage** (et combien de joueurs seront déconnectés) et ce qui **ne change pas** (liste blanche, opérateurs, `server.properties`, clé d'administration). « Restaurer et redémarrer » fait tout d'un coup ; « Au prochain démarrage seulement » la prévoit (un bandeau la rappelle, avec « Redémarrer maintenant » et « Annuler la restauration »). Le launcher suit le redémarrage, puis montre le compte rendu de la restauration, avec **« Revenir à l'état d'avant »** ;
- **effacer** une archive (corbeille), après confirmation ;
- les **réglages** des sauvegardes automatiques : en marche ou non, fréquence (de 15 minutes à une semaine), nombre d'archives automatiques gardées, sauvegarde à chaque arrêt, et même pendant une partie ou non. La prochaine sauvegarde automatique est annoncée.

Quand le serveur de ce PC est arrêté, l'onglet montre les archives de son dossier `backups/` (bouton « Afficher » dans l'Explorateur) : il faut le démarrer pour sauvegarder ou restaurer.

Les archives ne contiennent ni la clé d'administration, ni le webhook Discord, ni `server.properties` et les listes de joueurs. Le détail (contenu, rotation, restauration) est dans [docs/ADMIN-API.md](docs/ADMIN-API.md#les-sauvegardes). L'arrêt du serveur attend la fin de la sauvegarde d'arrêt : le service systemd lui laisse 10 minutes.

`sauvegarder.sh`, en plus, archive tout le dossier de façon brute (le monde, tout `config/` avec le webhook, `server.properties` et les listes de joueurs) et garde les 14 dernières archives dans `/opt/valcombe/sauvegardes/`. Utile une fois par nuit, en complément.

Sauvegarde brute chaque nuit à 5 h :

```bash
sudo crontab -u valcombe -e
```

Ajoutez la ligne :

```
0 5 * * * /opt/valcombe/sauvegarder.sh
```

Pour restaurer une archive brute : arrêtez le service, décompressez l'archive dans `/opt/valcombe/` (`tar -xzf sauvegardes/valcombe-AAAA-MM-JJ_HHMM.tar.gz`), puis relancez. Copiez de temps en temps une archive sur votre PC (celles du mod se téléchargent d'un clic dans l'onglet Sauvegardes du launcher) : une sauvegarde qui ne quitte jamais le serveur ne protège pas d'une panne du serveur.

## 7. Mettre à jour

1. Sur votre PC : `./gradlew build` puis `python tools/make_modpack.py --serveur=adresse-du-vps`.
2. Publiez la nouvelle version (le launcher des joueurs se met à jour tout seul).
3. Envoyez le nouveau dossier `mods/` sur le VPS (remplacez les anciens fichiers), puis `sudo systemctl restart valcombe`.

Une partie en cours est sauvegardée automatiquement et reprend après le redémarrage.

## 8. Piloter le serveur : l'API d'administration

Le mod ouvre, sur un serveur dédié, une API protégée par une clé : état du serveur (TPS, mémoire, joueurs), console en direct, commandes, liste blanche, opérateurs, message d'accueil, joueurs max, arrêt et redémarrage, sauvegardes (liste, téléchargement, restauration d'un clic). La page **Serveur** du launcher s'en sert pour piloter le serveur (voir plus bas) ; le contrat complet est dans [docs/ADMIN-API.md](docs/ADMIN-API.md).

- **La clé** s'affiche dans le journal au premier démarrage (`sudo journalctl -u valcombe | grep "Clé d'administration"`). Elle est gardée dans `/opt/valcombe/config/valcombe/admin.json`. Ensuite : `/vc admin cle` (console, ou opérateur en jeu) la réaffiche, `/vc admin nouvellecle` la remplace. Elle donne tout pouvoir sur le serveur : ne la partagez pas.
- **Elle n'accepte que la machine elle-même** (une requête venue d'ailleurs est refusée, même avec la clé) : rien à ouvrir dans le pare-feu. Depuis votre PC, passez par un tunnel SSH, puis parlez à `localhost` :

  ```bash
  ssh -N -L 25570:localhost:25570 utilisateur@adresse-du-vps
  ```

- **Redémarrer par l'API** : le serveur s'arrête proprement après avoir posé le drapeau `config/valcombe/redemarrer` ; `demarrer.sh` (donc le service systemd) le relance aussitôt, sans passer par `systemctl`. Un arrêt par l'API, lui, est définitif jusqu'au prochain `sudo systemctl start valcombe`. Si vous lancez le serveur autrement qu'avec `demarrer.sh` ou `demarrer.bat`, relancez-le vous-même.
- **La couper** : mettez `"enabled": false` dans `admin.json`, puis redémarrez. `/vc admin` dit si elle est ouverte et sur quel port.
- **Depuis le launcher** : page **Serveur**, bouton « Ajouter un serveur ».
  - **Serveur loué** (conseillé) : le parcours guidé de la partie « Depuis le launcher (SSH) », en tête de ce guide. Le launcher ouvre lui-même le tunnel SSH et lit la clé sur le serveur : rien à copier.
  - **Accès direct** : pour un serveur que votre PC joint par un réseau privé ou un VPN (avec `"adminLocalOnly": false` dans `admin.json`). Donnez un nom, l'adresse, le port de l'API (25570 s'il n'a pas changé) et la clé ; « Tester la connexion » vérifie le tout. La clé est gardée chiffrée sur votre PC (DPAPI de Windows). Un tunnel SSH ouvert à la main convient aussi (adresse `localhost`, et le port choisi sur votre PC, par exemple `ssh -N -L 25571:localhost:25570 utilisateur@adresse-du-vps`), mais la fiche de serveur loué le fait pour vous.
  - Dans tous les cas, la page montre l'état (version, durée, TPS, mémoire, joueurs), les joueurs connectés, les mondes, la console en direct avec l'envoi de commandes, les réglages (liste blanche, opérateurs, message d'accueil, prochaine soirée, joueurs max, port), les sauvegardes (partie 6), et les boutons Arrêter et Redémarrer ; pour un serveur loué, aussi Démarrer et Mettre à jour.
  - « Ce PC », dans la même liste, est le serveur que le launcher héberge sur votre PC (page Héberger) : la page Serveur le démarre, l'arrête et le redémarre aussi, et y règle en plus la mémoire et le port du jeu.

## Vie de la communauté

### Grimoire sur tablette

Le Conteur tape `/vc tablette` dans le jeu. Un lien s'affiche : il l'ouvre sur sa tablette ou son téléphone pour voir le grimoire, répondre aux décisions, tuer, ressusciter, empoisonner et faire avancer la partie, sans quitter l'écran du jeu.

- Le lien contient un jeton secret : ne le partagez pas. `/vc tablette` en génère un nouveau (l'ancien cesse de marcher), `/vc tablette fermer` coupe la page.
- La tablette agit au nom du Conteur, avec ses droits : il doit rester connecté au jeu.
- Sur un serveur loué, le lien affiché utilise l'adresse du serveur ; ouvrez le port 25570 (ou changez-le avec `/vc tablette port <numéro>`).
- Par défaut, la tablette ne répond qu'aux appareils du **réseau local** (même box que le serveur). Sur un serveur loué, autorisez l'accès par Internet avec `/vc tablette internet oui` (et `non` pour revenir au réseau local seul).
- La page n'est pas chiffrée (HTTP). Hors réseau local, fermez la tablette après la partie.

### L'Atelier sur un serveur loué, sans l'ouvrir à Internet

L'Atelier (`/vc atelier`) sert à créer rôles, scénarios et cartes : il donne beaucoup de pouvoir et sa page n'est pas chiffrée. Sur un VPS, le plus sûr est de **ne pas ouvrir le port 25570** et de passer par un tunnel SSH depuis votre PC :

```bash
ssh -L 25570:localhost:25570 utilisateur@adresse-du-vps
```

Laissez cette fenêtre ouverte, tapez `/vc atelier` dans le jeu, puis ouvrez dans votre navigateur le lien affiché **en remplaçant l'adresse par `localhost`** (exemple : `http://localhost:25570/atelier#…`). Pour le serveur, la page vient de la machine elle-même : elle est acceptée même avec la tablette réservée au réseau local. Le même tunnel marche pour la tablette du Conteur.

### Annoncer l'adresse du serveur aux launchers

- Le plus simple : chaque joueur ajoute le serveur dans le launcher (Réglages › Serveurs enregistrés), puis le choisit sur l'accueil.
- Pour tout le monde d'un coup : fabriquez la version avec `python tools/make_modpack.py --serveur=adresse-du-vps` ; après la publication, les launchers proposent cette adresse d'office (« Serveur de la version publiée »).
- `/vc soiree vendredi 21 h` annonce la prochaine soirée : elle s'affiche dans la liste des serveurs de Minecraft et sur l'accueil du launcher.

### Compte rendu sur Discord

1. Sur Discord : paramètres du salon › Intégrations › Webhooks › Nouveau webhook, puis « Copier l'URL du webhook ».
2. Dans le jeu, en opérateur : `/vc discord <URL copiée>`, puis `/vc discord essai` pour vérifier.
3. À chaque fin de partie, le salon reçoit le camp vainqueur, la raison, et le rôle de chacun.

`/vc discord aucun` désactive l'envoi. L'URL reste sur le serveur, dans `config/valcombe-communaute.json` : ne la publiez pas, elle permet d'écrire dans votre salon.

### Statistiques

- `/vc stats` : classement des joueurs (victoires et parties).
- `/vc stats <pseudo>` : parties, victoires, camps, rôles joués, parties menées comme Conteur.

Les statistiques sont dans `config/valcombe-stats.json` (incluses dans les sauvegardes). Les joueurs fictifs ne comptent pas.
