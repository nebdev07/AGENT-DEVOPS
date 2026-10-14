# Vos applications en ligne, sécurisées, sauvegardées

> Fiche client — version 2026-10-14 (prix : hypothèses de départ, `business.md`).  
> Règle : aucun nom de projet ou de client dans ce document sans accord écrit (ADR-022).

## Le problème

Votre application tourne sur un serveur (VPS) installé à la main. Souvent :

- la base de données est joignable depuis internet, le mot de passe SSH suffit pour entrer ;
- personne ne sait si les sauvegardes existent, ni si elles se restaurent ;
- chaque mise à jour de l'application est une opération risquée qui dépend d'une seule personne ;
- le certificat HTTPS expire sans prévenir.

Le jour d'un piratage, d'une panne de disque ou d'une erreur de manipulation, il est trop tard pour s'en occuper.

## Ce que nous faisons

Nous opérons votre serveur avec un outil d'automatisation qui suit toujours la même méthode : **analyser, proposer un plan, attendre votre accord, exécuter, prouver**.

1. **Audit** — analyse du serveur en lecture seule (rien n'est modifié). Vous recevez un rapport noté de A à E : risques classés par gravité, ce que nous avons observé, ce que cela peut coûter, ce qu'il faut faire.
2. **Mise en ligne** — sécurisation (pare-feu, accès SSH par clé, blocage des attaques par force brute), conteneurs isolés, HTTPS automatique, déploiement de vos applications depuis votre dépôt Git, reprise de vos données, sauvegardes chiffrées quotidiennes.
3. **Maintenance** — sauvegardes vérifiées chaque jour, **exercice de restauration chaque mois** (nous prouvons que la sauvegarde se réimporte), mises à jour de sécurité, déploiements à la demande, rapport mensuel.

## Nos engagements

- **Aucune modification sans votre accord** : chaque action sur le serveur est précédée d'un plan écrit.
- **Sauvegarde avant toute écriture** sur vos données ; retour automatique à l'état précédent si un import échoue.
- **Une sauvegarde ne compte que si elle se restaure** : le contenu de chaque copie est relu en entier, et un exercice de restauration est fait dans une base jetable, sans toucher à vos données.
- **Vos accès restent les vôtres** : nous utilisons un compte et une clé dédiés, retirés en fin de contrat. Mandat écrit (périmètre, horaires, interlocuteur).
- **Vous gardez votre hébergeur** : nous ne revendons pas de serveur ; vous restez propriétaire de tout.

## Tarifs de départ (HT, FCFA)

| Offre | Prix |
|---|---|
| Audit d'un serveur (rapport + restitution 30 min) | 75 000 — déduit de la mise en ligne si signée sous 30 jours |
| Mise en ligne Essentiel (1 application, 1 domaine) | 350 000 |
| Mise en ligne Standard (jusqu'à 3 applications, reprise des données) | 650 000 |
| Maintenance Veille (par serveur) | 35 000 / mois |
| Maintenance Pro (par serveur, déploiements inclus) | 75 000 / mois |

Engagement de maintenance : 6 mois. Paiement annuel : 2 mois offerts. Intervention hors forfait : 25 000 / h.

## Limites, dites clairement

- ~~Les sauvegardes automatiques couvrent les **bases de données** (MariaDB / MySQL). Les fichiers déposés par vos utilisateurs (images, pièces jointes) sont repris lors de la migration ; leur sauvegarde quotidienne fait l'objet d'une option à venir.~~ *(version du 2026-10-14 matin, remplacée)*
- Les sauvegardes automatiques couvrent les **bases de données** (MariaDB / MySQL) **et les fichiers déposés par vos utilisateurs** (images, pièces jointes) stockés par vos applications. Chaque sauvegarde est vérifiée ; un exercice de restauration prouve qu'elle se réimporte.
- La copie de sauvegarde hors serveur est conservée chez l'opérateur, chiffrée. Une copie vers un stockage externe de votre choix est prévue.
- Pas de surveillance 24 h / 24 : intervention aux heures ouvrées (Pro : le jour ouvré suivant).

## Comment démarrer

1. Vous nous donnez un accès temporaire en lecture (procédure fournie, 10 minutes).
2. Nous vous remettons le rapport d'audit sous 48 h ouvrées.
3. Si vous le souhaitez : devis de mise en ligne, mandat d'intervention, puis planification.
