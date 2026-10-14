# Procédure de retrait des accès en fin de mandat (BIZ-07)

> Version 2026-10-14. Annexe du mandat d'intervention (article 3.4). Délai : **2 jours ouvrés** après la fin du mandat.  
> Chaque étape dit **qui** la fait et **comment la vérifier**. Le Client peut refaire seul toutes les vérifications.  
> Statut : procédure **écrite**, pas encore exécutée sur un serveur client. La relecture juridique du mandat reste à faire (hors code).

## Ce que l'agent a laissé sur le serveur (et qui reste au Client)

Ces éléments appartiennent au Client et **continuent de fonctionner sans le Prestataire**. Ils ne sont pas supprimés, sauf demande écrite du Client (étape 9).

| Élément | Emplacement | Rôle |
|---|---|---|
| Sauvegarde quotidienne chiffrée | `/usr/local/bin/vps-autopilot-backup`, minuteur `vps-autopilot-backup.timer` (02:30) | Bases MariaDB + fichiers des volumes, 14 gardées dans `/opt/vps-autopilot/backups` |
| Exercice de restauration mensuel | `/usr/local/bin/vps-autopilot-backup-drill`, minuteur `vps-autopilot-backup-drill.timer` (1er du mois, 04:00) | Résultat dans `/opt/vps-autopilot/backups/drill-last.txt` |
| Phrase de chiffrement | `/opt/vps-autopilot/backups/.passphrase` (root, 600) | Ouvre les sauvegardes |
| Garde en ligne des plateformes | `/usr/local/bin/vps-autopilot-keepalive`, minuteur `vps-autopilot-keepalive.timer` | Relance les services arrêtés |
| Plateformes et applications | `/opt/vps-autopilot/platforms/<id>/`, `/opt/vps-autopilot/apps/`, conteneurs `plat-*`, `app-*` | Les sites du Client |
| Secrets d'exécution | `/opt/vps-autopilot/platforms/<id>/svc-*.local.env` (600, `deploy`) | Mots de passe des applications |
| Clés Git de lecture | `/home/deploy/.ssh/git-*` | Clone des dépôts du Client au déploiement |
| Compte `deploy` | sans mot de passe, sans sudo | Propriétaire des fichiers des plateformes |

## Étapes

### 1. Dernières preuves — Prestataire (avant le retrait)

1. Console : **Analyser** le serveur, puis **Rapport d'audit client** (bande Rapport) avec le nom du Client.
2. Bande **Sauvegardes** : **Sauvegarder maintenant**, puis **Exercice de restauration** sur la copie obtenue. Noter le résultat (tables et fichiers comptés).
3. Bande **Journal** : **Exporter (HTML)** et **Exporter (CSV)** — toutes les actions faites sur ce serveur pendant le mandat.
4. Remettre au Client : rapport d'audit, journal HTML, résultat de l'exercice.

*Vérification* : le Client a reçu les trois documents ; le journal se termine par l'exercice du jour.

### 2. Remise de la clé de chiffrement — Prestataire → Client

1. Remettre au Client le contenu de `state/backup.key` du poste d'exploitation (32 caractères hexadécimaux), **par un canal convenu à l'avance** (jamais par le même e-mail que les sauvegardes).
2. Rappeler que la même phrase est sur le serveur (`/opt/vps-autopilot/backups/.passphrase`, lisible par root seul) : les sauvegardes du serveur restent lisibles par le Client.

*Vérification (Client)* : `sudo openssl enc -d -aes-256-cbc -pbkdf2 -pass file:/opt/vps-autopilot/backups/.passphrase -in <dernier dump-*.sql.gz.enc> | gzip -dc | tail -n 1` affiche `-- Dump completed …`.

### 3. Secrets connus du Prestataire — Client

Le Prestataire a pu connaître des mots de passe pendant le mandat (boîte mail d'envoi, comptes d'hébergeur, tokens). La console ne les réaffiche jamais, mais le Prestataire les a saisis.

1. Le Client change ces mots de passe chez leurs fournisseurs.
2. Pour une valeur posée avec **Secrets d'exécution** : la modifier dans `svc-<service>.local.env` (`sudo nano`), puis `sudo docker compose --project-directory /opt/vps-autopilot/platforms/<id> -f /opt/vps-autopilot/platforms/<id>/docker-compose.yml up -d --no-build <service>`.

*Vérification* : le service redémarre et fonctionne (envoi de test pour un mot de passe SMTP).

### 4. Clés Git de déploiement — Client (ou Prestataire avec ses accès au dépôt)

1. Sur GitHub / GitLab, pour chaque dépôt du Client : **Settings › Deploy keys** → supprimer les clés `vps-autopilot-git-readonly…`.
2. Facultatif : si le Client garde les déploiements automatiques, il crée ses propres Deploy keys et remplace `/home/deploy/.ssh/git-*` sur le serveur.

*Vérification* : la page Deploy keys du dépôt ne liste plus de clé de l'agent.

### 5. Droits root temporaires — Prestataire

1. Supprimer les fichiers sudo de l'agent : `sudo rm -f /etc/sudoers.d/90-vps-autopilot-temp /etc/sudoers.d/<compte de l'agent>`.
2. Si le compte de l'agent avait été ajouté à un groupe d'administration (`sudo`, `docker`) : `sudo gpasswd -d <compte> sudo`, idem pour `docker`.

*Vérification (Client)* : `sudo ls /etc/sudoers.d/` ne contient plus de fichier de l'agent ; `sudo -l -U <compte>` ne donne aucun droit.

### 6. Clé SSH de l'agent — Prestataire, puis Client

1. Sur le serveur, retirer la ligne de la clé de l'agent (fin de ligne `vps-autopilot` ou empreinte communiquée au Client) dans `~<compte>/.ssh/authorized_keys` **de chaque compte** où elle a été posée (compte de l'agent, éventuellement `ubuntu` / `root`) :
   `sudo sed -i '/<début unique de la clé publique>/d' /home/<compte>/.ssh/authorized_keys`
2. Si le compte de l'agent est dédié et n'a plus d'usage : `sudo userdel -r <compte>` (après l'étape 5).
3. Si `AllowUsers` de sshd cite ce compte : le retirer, puis `sudo sshd -t && sudo systemctl reload ssh`.

*Vérification (Client)* : depuis le poste du Prestataire, en sa présence ou par capture : `ssh -i ~/.ssh/vps-autopilot <compte>@<serveur>` est **refusé** (`Permission denied (publickey)`). Sur le serveur : `sudo grep -r "<début de la clé>" /home/*/.ssh /root/.ssh` ne renvoie rien.

### 7. Poste d'exploitation — Prestataire

1. Copies locales des sauvegardes : supprimer `state/backups/<serveur>/` après la durée de conservation prévue au mandat (article 7.2), ou tout de suite si le Client le demande.
2. Console : **Oublier** le serveur (fiche locale) et retirer ses plateformes et applications de la liste (rien n'est arrêté sur le serveur).
3. Garder le journal `state/journal/<serveur>.jsonl` pendant la durée de preuve convenue (litige, garantie), puis le supprimer. Il ne contient ni SQL ni mot de passe.
4. Supprimer l'empreinte SSH du serveur dans `state/trusted-hosts.json` (empreintes acceptées par l'agent) et, si le Prestataire s'y est connecté à la main, dans son `~/.ssh/known_hosts` (`ssh-keygen -R <serveur>`).

*Vérification* : attestation sur l'honneur du Prestataire (étape 8).

### 8. Attestation de retrait — Prestataire → Client

Document daté et signé, remis au Client :

> Je soussigné [nom], pour [Prestataire], atteste avoir, le [date] :
> - remis le rapport d'audit final, le journal des interventions et le résultat du dernier exercice de restauration ;
> - remis la clé de chiffrement des sauvegardes ;
> - retiré la clé SSH de l'agent des comptes [liste] du serveur [nom / adresse], et les droits sudo associés ;
> - [supprimé / conservé jusqu'au [date]] les copies de sauvegarde détenues sur mon poste ;
> et n'avoir conservé aucun autre accès au serveur.

### 9. Facultatif — retrait complet de l'outillage (sur demande écrite du Client)

Seulement si le Client ne veut plus des sauvegardes et exercices automatiques (déconseillé sans solution de remplacement) :

```bash
sudo systemctl disable --now vps-autopilot-backup.timer vps-autopilot-backup-drill.timer vps-autopilot-keepalive.timer
sudo rm -f /etc/systemd/system/vps-autopilot-{backup,backup-drill,keepalive}.{service,timer}
sudo rm -f /usr/local/bin/vps-autopilot-{backup,backup-drill,keepalive}
sudo systemctl daemon-reload
```

Les sauvegardes déjà faites (`/opt/vps-autopilot/backups`) et les sites ne sont **pas** supprimés par ces commandes.

## Limites connues

- L'agent utilise aujourd'hui **une seule clé SSH par poste d'exploitation** (`~/.ssh/vps-autopilot`), pas une clé par Client comme le prévoit l'article 3.1 du mandat. Tant que ce n'est pas fait, l'étape 6 doit être faite sur **chaque** serveur du Client, et la clé ne doit être posée que sur les serveurs sous mandat.
- Le retrait n'est pas automatisé depuis la console : il suit cette procédure à la main, avec une vérification par le Client.
