# Les Cendres de Valcombe — mod Minecraft

Mod Fabric pour Minecraft Java **26.3**. Il ajoute un jeu de déduction sociale à Conteur. Règles et rôles : voir le document « Les Cendres de Valcombe — Rôles et règles ».

Le mod tourne côté serveur. Les joueurs n'ont besoin d'aucun mod : tout passe par le chat (boutons cliquables) et les commandes `/vc`. Chaque joueur doit avoir un compte Minecraft officiel.

## Organisation

| Dossier | Contenu |
| --- | --- |
| `src/main/java/fr/valcombe/rules/` | Moteur de règles : 22 rôles, nuits, votes, victoire. Aucune dépendance à Minecraft. |
| `src/main/java/fr/valcombe/session/` | Déroulement d'une table : inscriptions, Conteur, réveils, votes, boutons du chat |
| `src/main/java/fr/valcombe/mc/` | Pont avec Minecraft : commandes, messages, placement en cercle, cécité la nuit |
| `src/test/java/` | Tests JUnit : règles rôle par rôle, et 80 parties complètes simulées |

## Compiler et tester

Il faut Java 25 (Temurin).

```bash
./gradlew build
```

Le mod compilé se trouve dans `build/libs/valcombe-0.1.0.jar`. Pour l'installer sur un serveur Fabric 26.3, copiez ce fichier dans son dossier `mods`, avec Fabric API.

Pour lancer un serveur de développement, il faut d'abord accepter le contrat d'utilisation de Minecraft, dans `run/eula.txt`. Ensuite :

```bash
./gradlew runServer
```

## Jouer

| Commande | Qui | Effet |
| --- | --- | --- |
| `/vc rejoindre`, `/vc quitter` | Tous | S'inscrire ou se désinscrire (ordre d'inscription = ordre du cercle) |
| `/vc conteur` | Opérateur | Devenir le Conteur humain (il ne joue pas) |
| `/vc lancer [auto\|humain]` | Opérateur ou Conteur | Distribuer les rôles et placer les joueurs en cercle autour de soi |
| `/vc nuit`, `/vc finjour` | Opérateur ou Conteur | Faire tomber la nuit, clore la journée |
| `/vc suivant` | Opérateur ou Conteur | Passer un réveil bloqué (joueur absent) |
| `/vc nominer <joueur>` | Joueurs vivants | Nominer ; chacun reçoit des boutons pour lever la main |
| `/vc tirer <joueur>` | Joueurs vivants | Tir de l'Arbalétrier (tout le monde peut bluffer) |
| `/vc role`, `/vc fiches` | Tous | Revoir son rôle, lire toutes les fiches |
| `/vc grimoire` | Conteur | Ouvrir le grimoire |
| `/vc mp <message>` | Tous | Message privé au Conteur |
| `/vc historique` | Tous | Historique de la dernière partie |
| `/vc reglage <clé> <valeur>` | Opérateur ou Conteur | `nuit_immobile oui\|non`, `nuit_sombre oui\|non`, `temps_vote <s>` |

La nuit, chaque joueur réveillé reçoit dans le chat des boutons pour désigner des joueurs, puis son information. En mode `humain`, le Conteur relit chaque information et peut la corriger avant l'envoi.
