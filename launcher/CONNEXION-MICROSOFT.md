# Connexion Microsoft intégrée au launcher — démarche

Aujourd'hui, le launcher confie la connexion à Prism Launcher : le compte Microsoft y est enregistré une fois pour toutes. Pour que le launcher Valcombe se connecte lui-même, Microsoft demande deux choses. C'est gratuit, mais l'accord prend de quelques jours à quelques semaines.

## 1. Déclarer l'application sur Azure

1. Ouvrez https://portal.azure.com avec un compte Microsoft (le vôtre convient).
2. Allez dans **Microsoft Entra ID → Inscriptions d'applications → Nouvelle inscription**.
   - Nom : `Valcombe Launcher`.
   - Types de comptes : **Comptes Microsoft personnels uniquement**.
   - URI de redirection : plateforme **Client public/natif (mobile et bureau)**, valeur `http://localhost`.
3. Dans **Authentification**, activez **Autoriser les flux clients publics** (connexion par code affiché à l'écran).
4. Notez l'**ID d'application (client)** affiché sur la page « Vue d'ensemble ». Ce n'est pas un secret.

## 2. Demander l'accès aux services Minecraft

Les nouvelles applications sont refusées par les services Minecraft tant que Microsoft ne les a pas validées. Déposez la demande avec le formulaire officiel de validation des applications tierces, en indiquant l'ID d'application : https://aka.ms/mce-reviewappid

Je n'ai pas pu vérifier que ce lien est toujours le bon. S'il ne fonctionne plus, cherchez « Minecraft AppID review form » sur le site d'aide de Microsoft.

Décrivez le launcher honnêtement : un launcher privé pour une communauté d'amis, qui connecte des comptes ayant acheté le jeu et lance Minecraft avec un mod.

## 3. Ensuite

Quand Microsoft a donné son accord, envoyez-moi l'ID d'application. J'ajouterai au launcher :

- la connexion par code (« Rendez-vous sur microsoft.com/link et saisissez ce code »), sans mot de passe tapé dans le launcher ;
- l'enregistrement du compte, chiffré avec la protection Windows (DPAPI) dans `ValcombeData`, pour ne pas se reconnecter à chaque fois ;
- le lancement direct du jeu, sans passer par Prism.

En attendant, tout fonctionne déjà avec Prism.
