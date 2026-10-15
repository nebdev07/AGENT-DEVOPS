# Monétisation — VPS Autopilot

> Statut : draft. Enrichir, ne jamais effacer.  
> Dernière mise à jour : 2026-10-14 (grille de prix, documents commerciaux)  
> Décision : ADR-039. Tâches : backlog `BIZ-*`.

## Thèse

Beaucoup de structures (PME, ONG, associations, administrations, agences web) font tourner leurs applications sur un VPS monté à la main : Apache, Tomcat, PM2, MySQL exposé, pas de sauvegarde testée, pas d'isolation. Elles ne veulent pas apprendre Docker, Traefik ou Let's Encrypt. Elles veulent une application **en ligne, sécurisée, sauvegardée**.

L'agent transforme des jours de travail manuel en heures. On vend **ce résultat**, pas le logiciel, tant que le logiciel n'est pas utilisable sans son auteur.

## Ce qui est vendable aujourd'hui (implémenté)

| Capacité | Preuve |
|---|---|
| Analyse en lecture seule d'un serveur (aucune modification) | Discovery V0, serveur DTSI analysé et inventorié |
| Sécurisation de base (UFW, fail2ban, SSH durci) | ADR-019, lab + VPS ARC |
| Docker + Traefik + HTTPS automatique | ADR-033 |
| Mise en ligne d'une plateforme N services depuis Git (sans Dockerfile côté client) | V7-06, ARC sur `arc-nwanyo.org` en 6 min |
| Restauration de données d'un ancien serveur (avec retour arrière) | `restore-platform`, ARC |
| Relance automatique des conteneurs, console SQL protégée | 2026-10-01 / 02 |

### Ajouts du 2026-10-14 (plan A → D, `plan-commercial.md`)

| Capacité | Preuve |
|---|---|
| Déploiements et restaurations longs qui survivent à une coupure SSH (BIZ-01, ADR-040) | Lab : tâche de 6 min récupérée, code retour transmis |
| Rapport d'audit client : note A–E, constats classés, recommandations, HTML imprimable en PDF (BIZ-04) | Lab : rapport C 62/100, puis A 91/100 après correction |
| Sauvegardes MariaDB réelles : quotidiennes, chiffrées, copiées sur le poste, contenu vérifié, exercice de restauration, restauration d'une plateforme depuis une sauvegarde (BIZ-03, ADR-041) | Lab : dump 4 Mo, exercice 25/25 tables ; exercice mensuel automatique sur le serveur (BIZ-12) |

### Ajouts du 2026-10-15

| Capacité | Preuve |
|---|---|
| Alertes e-mail envoyées par le serveur au client et à l'opérateur : échec de sauvegarde ou d'exercice, résumé mensuel de l'exercice réussi (BIZ-15, ADR-044) | Lab : panne simulée → alerte reçue par les 2 destinataires ; résumé 25/25 tables |
| Version 1.0.0 installable sans les sources (zip, démarrage Windows / Linux, notice), console limitée au poste (ADR-045) | Installation à blanc sur le poste |

Pour l'offre **Veille** : poser les alertes avec un compte SMTP du prestataire ou du client, destinataires = client + opérateur. Toujours **pas** de supervision 24/7 : un serveur éteint n'envoie rien (BIZ-16).

## Ce qui n'est pas encore vendable (spécifié ou absent)

~~Restauration complète depuis les sauvegardes de l'agent~~ (livrée 2026-10-14 pour les bases MariaDB), ~~rapport client exportable~~ (livré 2026-10-14), sauvegardes hors-site autres que le poste opérateur (BIZ-09), ~~**sauvegarde des fichiers déposés par les utilisateurs (volumes `/app/data`) : non couverte par les sauvegardes de l'agent** (BIZ-11)~~ (livrée 2026-10-14 ; restauration depuis la console pour les plateformes, pas encore pour les apps 1:1), comptes multi-opérateurs (V9), installation de l'agent par un tiers, déploiement sans internet côté serveur. Voir `BIZ-*` dans le backlog.

## Trois étapes, dans cet ordre

### Étape 1 — Service géré (maintenant)

L'agent reste un **outil interne**. Le client achète une prestation.

| Offre | Contenu | Rôle commercial |
|---|---|---|
| **Audit** | Analyse lecture seule + rapport (risques, ports exposés, sauvegardes, versions) | Porte d'entrée, risque nul pour le client |
| **Mise en ligne** (forfait) | Sécurisation, Docker/Traefik/HTTPS, plateforme(s), restauration des données, sauvegardes, rapport avant/après | Revenu principal ponctuel |
| **Maintenance** (mensuel) | Sauvegardes vérifiées, déploiements à la demande, relance auto, contrôle HTTPS, intervention | Revenu récurrent |

**Cibles prioritaires** : structures déjà dans le réseau (forme ARC, forme DTSI) ; agences et développeurs indépendants qui livrent des applications mais ne savent pas les héberger (canal de revente : ils vendent, on opère).

**Prix** : ~~pas encore fixés~~ grille de départ ci-dessous (2026-10-14), à confirmer sur les deux premiers devis réels. Méthode : prix du forfait = fraction du coût de l'alternative (jours d'un prestataire infra, coût d'un incident sans sauvegarde), maintenance = abonnement mensuel par serveur, éventuellement par plateforme au-delà d'un seuil.

## Grille de prix de départ (hypothèses, 2026-10-14)

> Statut : **hypothèse à valider**, pas un tarif publié. Monnaie : FCFA (XOF), hors taxes. L'hébergement (VPS, nom de domaine) est payé directement par le client chez son hébergeur : on ne revend pas de serveur.

### Hypothèses de calcul

| Hypothèse | Valeur retenue | À vérifier |
|---|---|---|
| Journée d'un prestataire infra indépendant (marché UEMOA) | 100 000 – 150 000 FCFA | 3 devis concurrents |
| Journée d'une agence / ESN | 200 000 – 300 000 FCFA | idem |
| Mise en ligne propre faite à la main (sécurisation, Docker, HTTPS, données, sauvegardes testées) | 5 à 10 jours | retour terrain |
| Même travail avec l'agent | 1 jour (dont ~2 h machine, le reste : échanges, DNS, contrôle) | chronométrer les 3 premiers clients |
| Taux horaire cible de l'opérateur | 25 000 FCFA | — |

Règle : le forfait se situe entre **30 % et 50 % du coût manuel chez un indépendant**. Le client paie nettement moins cher ; l'opérateur gagne plusieurs fois son taux horaire grâce à l'outil.

### Offres

| Offre | Contenu | Prix HT |
|---|---|---|
| **Audit** (1 serveur) | Analyse en lecture seule, rapport A–E (PDF), restitution de 30 min | **75 000** — déduit de la Mise en ligne si signée sous 30 jours |
| **Mise en ligne Essentiel** | 1 serveur, 1 plateforme (≤ 3 services) ou 1 app, 1 domaine, HTTPS, sécurisation, sauvegardes quotidiennes + 1 exercice de restauration, rapport avant/après | **350 000** |
| **Mise en ligne Standard** | 1 serveur, jusqu'à 3 plateformes (≤ 8 services), reprise des données d'un ancien serveur (base + fichiers), le reste comme Essentiel | **650 000** |
| **Sur devis** | Plusieurs serveurs, données > 20 Go, applications sans dépôt Git, contraintes réseau (pas d'internet côté serveur) | à partir de 900 000 |

| Maintenance (par serveur, mensuel) | Contenu | Prix HT / mois |
|---|---|---|
| **Veille** | Sauvegardes quotidiennes vérifiées (copie hors serveur), 1 exercice de restauration par mois, rapport d'audit mensuel, mises à jour de sécurité, 1 h d'intervention | **35 000** |
| **Pro** | Veille + déploiements à la demande (≤ 4 / mois), contrôle HTTPS et relance, intervention le jour ouvré suivant, 3 h d'intervention | **75 000** |

- Engagement 6 mois. Paiement annuel : 10 mois facturés pour 12.
- Hors forfait : 25 000 / h ; urgence (soir, week-end) : 40 000 / h.
- Acompte 50 % à la commande, solde à la mise en ligne (recette signée).

### Ce qu'on ne promet pas encore

- ~~Les **fichiers déposés par les utilisateurs** (images, pièces jointes) ne sont pas dans les sauvegardes automatiques de l'agent (BIZ-11) : en Standard, ils sont repris une fois lors de la migration ; en maintenance, les sauvegarder par un moyen manuel documenté ou attendre BIZ-11.~~ (obsolète 2026-10-14 : BIZ-11 livré, les fichiers des volumes sont dans la sauvegarde quotidienne. Un serveur équipé avant doit avoir le rôle réinstallé ; tant que ce n'est pas fait, ne pas le promettre pour ce serveur.)
- La copie hors serveur est **sur le poste de l'opérateur** (BIZ-09 : stockage objet). Le dire au client.
- Pas de garantie de disponibilité chiffrée (SLA) : pas de supervision 24/7.

### Seuils de rentabilité (indicatifs)

Avec 4 Mises en ligne Essentiel par mois (1 j chacune) et 10 serveurs en Veille (≈ 2 h / mois chacun), le revenu mensuel ≈ 1 400 000 + 350 000 = 1 750 000 FCFA HT pour ≈ 7 jours de travail. Le récurrent seul (10 × 35 000) couvre le coût fixe d'un outil et d'un poste ; viser 20 serveurs en maintenance avant la licence (étape 2).

### Documents commerciaux

Dans `.docs/commercial/` : `offre.md` (fiche client, sans nom de projet personnel, ADR-022), `devis-modele.md`, `mandat-intervention.md` (BIZ-07). Faire relire le mandat par un juriste (droit OHADA / pays du client) avant le premier contrat.

### Étape 2 — Licence auto-hébergée (après plusieurs clients du service)

Une agence installe l'agent chez elle et gère ses propres serveurs. Le client garde ses clés SSH (risque faible pour nous). Prérequis : installation par un tiers sans le porteur, aide publique complète (ADR-022, ADR-027), support.

### Étape 3 — Plateforme en ligne (V9, ADR-031)

Comptes, sessions, paramètres par compte. Plus grand potentiel, plus grand risque : on détiendrait des accès root à des serveurs tiers. Ne démarre que si les étapes 1 et 2 prouvent la demande. La facturation reste un non-objectif de V9 jour 1 (ADR-031).

## Risques et garde-fous

- **Accès aux serveurs clients** : mandat écrit (périmètre, horaires, interlocuteur), clé dédiée par client, retrait des accès documenté en fin de mission.
- **Responsabilité** : jamais de CHANGE sans plan confirmé (ADR-003) ; sauvegarde avant toute écriture ; journal des actions remis au client (implémenté 2026-10-14 : journal par serveur, export HTML/CSV, BIZ-06 / ADR-042).
- **Promesses** : ne pas vendre « données protégées » tant que la restauration depuis les sauvegardes de l'agent n'est pas livrée et testée (`BIZ-03`).
- **Références** : citer un client (ARC, DTSI) seulement avec son accord. La copie publique de l'agent reste sans projet personnel (ADR-022).

## Indicateurs à suivre

Clients actifs, revenu mensuel récurrent, durée moyenne d'une mise en ligne, incidents par mois, sauvegardes restaurées avec succès lors des tests.
