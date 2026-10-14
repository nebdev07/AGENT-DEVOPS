# Modèle de devis

> Modèle — version 2026-10-14. Remplacer les `[…]`. Prix de référence : `business.md` (hypothèses).  
> Aucun nom de projet personnel dans la version envoyée (ADR-022).

---

**[Raison sociale du prestataire]** — [adresse] — [RCCM] — [NCC / NIU] — [téléphone] — [e-mail]

**DEVIS N° [AAAA-NNN]** — émis le [JJ/MM/AAAA] — valable 30 jours

**Client** : [raison sociale] — [adresse] — [interlocuteur, fonction] — [e-mail / téléphone]

**Objet** : [Mise en ligne sécurisée de … / Maintenance du serveur …]

**Serveur concerné** : [hébergeur], [adresse IP ou nom], [système] — hébergement payé directement par le client.

## Prestations

| # | Désignation | Qté | Prix unitaire HT | Total HT |
|---|---|---|---|---|
| 1 | Audit du serveur (lecture seule, rapport A–E, restitution 30 min) | 1 | 75 000 | 75 000 |
| 2 | Mise en ligne [Essentiel / Standard] : sécurisation, conteneurs, HTTPS, déploiement de [liste des applications], sauvegardes quotidiennes + exercice de restauration, rapport avant / après | 1 | [350 000 / 650 000] | […] |
| 3 | Reprise des données de l'ancien serveur ([base(s)], [fichiers], volume estimé [x] Go) | [1] | [inclus en Standard] | […] |
| 4 | Remise : audit déduit (mise en ligne signée sous 30 jours) | 1 | −75 000 | −75 000 |
| 5 | Maintenance [Veille / Pro] — par serveur, engagement 6 mois | 6 | [35 000 / 75 000] | […] |

| | |
|---|---|
| **Total HT** | […] |
| TVA ([taux selon pays et régime fiscal du prestataire]) | […] |
| **Total TTC** | […] |

## Livrables

- Rapport d'audit (avant) et rapport d'audit (après mise en ligne).
- Applications accessibles en HTTPS sur [domaine(s)].
- Preuve de sauvegarde : fichier daté, contenu vérifié, exercice de restauration réussi (tables restaurées / attendues).
- Journal des actions réalisées sur le serveur.

## Prérequis côté client

- Accès au serveur selon le mandat d'intervention (compte et clé dédiés).
- Accès en lecture aux dépôts Git des applications.
- Accès à la zone DNS du domaine (ou un interlocuteur capable de modifier les enregistrements).
- Export des données de l'ancien serveur si la reprise est commandée.

## Hors périmètre

Développement ou correction du code des applications ; support aux utilisateurs finaux ; sauvegarde quotidienne des fichiers déposés par les utilisateurs (option à venir) ; surveillance 24 h / 24 ; frais d'hébergement et de nom de domaine.

## Conditions

- Acompte de 50 % à la commande, solde à la recette (procès-verbal signé).
- Maintenance facturée mensuellement à terme à échoir ; paiement annuel : 10 mois facturés pour 12.
- Intervention hors forfait : 25 000 HT / h ; urgence (soir, week-end, jour férié) : 40 000 HT / h.
- Toute intervention sur le serveur est encadrée par le **mandat d'intervention** signé (annexe).
- Délai de démarrage : [x] jours ouvrés après réception de l'acompte et des accès.

**Bon pour accord** (date, nom, signature, cachet du client) :

&nbsp;

&nbsp;
