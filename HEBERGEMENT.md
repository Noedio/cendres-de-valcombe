# Les Cendres de Valcombe — héberger le serveur

Deux façons d'accueillir vos amis :

- **Sur votre PC** : le plus simple pour commencer. Voir [INSTALLATION.md](INSTALLATION.md), partie « Pour l'hôte ».
- **Sur un petit serveur loué** (VPS Linux) : le serveur reste allumé sans votre PC. C'est l'objet de ce guide.

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

Pour taper des commandes de console (`whitelist add`, `op`), connectez-vous au jeu avec un compte opérateur. Vous pouvez aussi activer RCON dans `server.properties` et utiliser un client RCON, en gardant le port RCON fermé au public.

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

Ouvrez 25570 seulement les soirs où la tablette sert, puis refermez-le (`sudo ufw allow 25570/tcp` et `sudo ufw delete allow 25570/tcp`). Chez certains hébergeurs, il faut aussi ouvrir les ports dans leur interface web.

## 6. Sauvegardes automatiques

`sauvegarder.sh` archive le monde, les réglages de Valcombe (`config/` : village, scripts, partie en cours, statistiques, webhook) et les listes de joueurs. Il garde les 14 dernières archives dans `/opt/valcombe/sauvegardes/`.

Sauvegarde chaque nuit à 5 h :

```bash
sudo crontab -u valcombe -e
```

Ajoutez la ligne :

```
0 5 * * * /opt/valcombe/sauvegarder.sh
```

Pour restaurer : arrêtez le service, décompressez l'archive dans `/opt/valcombe/` (`tar -xzf sauvegardes/valcombe-AAAA-MM-JJ_HHMM.tar.gz`), puis relancez. Copiez de temps en temps une archive sur votre PC : une sauvegarde qui ne quitte jamais le serveur ne protège pas d'une panne du serveur.

## 7. Mettre à jour

1. Sur votre PC : `./gradlew build` puis `python tools/make_modpack.py --serveur=adresse-du-vps`.
2. Publiez la nouvelle version (le launcher des joueurs se met à jour tout seul).
3. Envoyez le nouveau dossier `mods/` sur le VPS (remplacez les anciens fichiers), puis `sudo systemctl restart valcombe`.

Une partie en cours est sauvegardée automatiquement et reprend après le redémarrage.

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
