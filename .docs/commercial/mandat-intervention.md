# Mandat d'intervention sur serveur (BIZ-07)

> Modèle — version 2026-10-14. **À faire relire par un juriste** (droit OHADA et droit du pays du client) avant le premier contrat.  
> Annexe obligatoire de tout devis comprenant une intervention sur un serveur du client.

---

Entre **[raison sociale du client]**, représenté par [nom, fonction], ci-après « le Client »,

et **[raison sociale du prestataire]**, représenté par [nom, fonction], ci-après « le Prestataire »,

il est convenu ce qui suit.

## 1. Objet

Le Client autorise le Prestataire à intervenir sur le(s) serveur(s) désigné(s) à l'article 2, dans les seules limites du présent mandat, pour exécuter les prestations du devis N° [AAAA-NNN].

## 2. Périmètre

| Serveur | Hébergeur | Adresse | Rôle (production / test) | Applications concernées |
|---|---|---|---|---|
| [nom] | [hébergeur] | [IP / nom] | [production] | [liste] |

Tout serveur, base de données ou service qui n'apparaît pas dans ce tableau est **hors mandat** : le Prestataire n'y accède pas, même si les identifiants le permettent.

## 3. Accès

1. Le Prestataire utilise un **compte dédié** ([nom du compte]) et une **clé SSH dédiée** au Client. Aucun mot de passe n'est transmis par e-mail ou messagerie.
2. Les droits d'administration (sudo) sont accordés à ce compte pour la durée du mandat.
3. Le Client peut suspendre l'accès à tout moment ; il en informe le Prestataire par écrit.
4. **En fin de mandat**, le Prestataire retire sa clé et son compte, puis remet au Client une attestation de retrait. Le Client peut le vérifier lui-même (procédure fournie).

## 4. Nature des actions

| Type | Exemples | Règle |
|---|---|---|
| **Lecture** | Analyse, audit, consultation des journaux | Autorisée pendant toute la durée du mandat |
| **Modification** | Installation, déploiement, mise à jour, sauvegarde, exercice de restauration | Précédée d'un plan écrit ; exécutée après accord du Client (ou dans le cadre d'une opération récurrente listée à l'article 5) |
| **Remplacement de données** | Restauration d'une base, reprise de données | Accord **écrit et spécifique** du Client à chaque fois ; sauvegarde de l'existant préalable obligatoire |
| **Destruction** | Suppression de base, de volume, de serveur | Interdite dans le cadre de ce mandat, sauf avenant signé |

## 5. Opérations récurrentes pré-autorisées (maintenance)

Le Client autorise sans nouvel accord : les sauvegardes quotidiennes et leur copie hors serveur ; l'exercice de restauration mensuel (base jetable supprimée à la fin, données de production non touchées) ; les mises à jour de sécurité du système ; le redémarrage des services arrêtés ; [déploiements de la branche [x] à la demande écrite de [interlocuteur]].

## 6. Horaires et interlocuteurs

- Fenêtre d'intervention pour les modifications : [jours, heures, fuseau]. Hors fenêtre : uniquement en cas d'incident, après appel à l'interlocuteur.
- Interlocuteur du Client habilité à donner les accords : [nom, fonction, téléphone, e-mail]. Suppléant : [nom].
- Interlocuteur du Prestataire : [nom, téléphone, e-mail].

## 7. Sauvegardes et données

1. Les sauvegardes sont chiffrées sur le serveur avant toute copie. La clé de chiffrement est conservée par le Prestataire et **remise au Client** sur demande et en fin de mandat.
2. Sur le serveur, les 14 sauvegardes les plus récentes sont conservées. Les copies hors serveur sont conservées par le Prestataire [sur son poste d'exploitation / stockage désigné] pendant [durée convenue], puis supprimées.
3. Le Prestataire n'exploite les données du Client à aucune autre fin que l'exécution du mandat. Il ne les communique à aucun tiers.
4. Les sauvegardes automatiques couvrent les bases de données ; les fichiers déposés par les utilisateurs n'y sont pas inclus à la date du mandat (voir devis).

## 8. Journal et compte rendu

Le Prestataire tient un journal des actions de modification (date, action, résultat). Il est remis au Client à chaque livraison et sur demande. Les rapports d'audit sont remis avant et après la mise en ligne, puis chaque mois en maintenance.

## 9. Confidentialité

Les parties gardent confidentielles les informations techniques échangées (adresses, comptes, architecture, données). Le Prestataire ne cite le Client comme référence qu'avec son **accord écrit**.

## 10. Responsabilité

1. Le Prestataire est tenu à une obligation de moyens. Il applique la méthode « plan, accord, exécution, preuve » et la sauvegarde préalable décrites ci-dessus.
2. Il n'est pas responsable des défaillances de l'hébergeur, du code des applications, ni des actions de tiers disposant d'un accès au serveur.
3. Sa responsabilité totale est limitée au montant payé par le Client au titre du devis sur les 12 derniers mois.

## 11. Durée et fin

Le mandat prend effet à la signature et dure [jusqu'à la recette / la durée de la maintenance]. Il prend fin automatiquement à l'échéance, ou à tout moment sur dénonciation écrite de l'une des parties avec un préavis de [15] jours. L'article 3.4 (retrait des accès) s'applique dans les 2 jours ouvrés suivant la fin.

Fait à [ville], le [date], en deux exemplaires.

| Pour le Client | Pour le Prestataire |
|---|---|
| [nom, fonction, signature, cachet] | [nom, fonction, signature, cachet] |
