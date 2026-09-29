# Documentation — catalogue et reprise

> Fichier vivant.  
> Dernière mise à jour : 2026-09-29

## Objectif

Un agent, une IA ou un développeur doit pouvoir **reprendre sans le porteur** : lire ce fichier, suivre le parcours, ne rien inventer qui contredise un ADR.

## Parcours de reprise (ordre)

1. [context.md](context.md) — pourquoi ce produit existe  
2. [current_state.md](current_state.md) — ce qui est **vraiment** là  
3. [architecture.md](architecture.md) + [decisions.md](decisions.md) — comment c’est construit  
4. [security.md](security.md) — ce qui est interdit  
5. [api.md](api.md) — contrats HTTP  
6. [roadmap.md](roadmap.md) + [backlog.md](backlog.md) + [next-session.md](next-session.md) — quoi faire ensuite  
7. `README.md` à la racine — commandes pour lancer  

Règles Cursor : [`.cursor/rules.md`](../.cursor/rules.md).

## Statuts

| Statut | Sens |
|--------|------|
| `planned` | Prévu, pas commencé |
| `draft` | Utilisable en interne |
| `published` | Relu, prêt pour reprise |
| `obsolete` | Remplacé ; ne pas effacer |

## Catalogue `.docs/`

| Doc | Statut | Rôle |
|---|---|---|
| `documentation.md` | published | Ce catalogue + parcours |
| `context.md` | published | Produit, lab, contraintes |
| `vision.md` | published | Objectif long terme |
| `current_state.md` | published | Spécifié vs implémenté |
| `architecture.md` | published | Couches, modules, flux |
| `decisions.md` | published | ADR-001 → ADR-036 |
| `security.md` | published | Risques, SSH, cibles interdites |
| `api.md` | published | Endpoints V0–V7-05 |
| `conventions.md` | published | Gouvernance + code |
| `glossary.md` | published | Vocabulaire |
| `roadmap.md` | published | V0 → V9 |
| `backlog.md` | draft | Items ouverts |
| `changelog.md` | published | Chronologie (ne jamais effacer) |
| `next-session.md` | published | Suite immédiate |

Hors `.docs/` : `README.md`, `infrastructure/lab/README.md`, `.env.example`.

## Emplacements (comme les autres projets)

| Audience | Chemin |
|----------|--------|
| Mémoire / ADR / API | `.docs/` |
| Guide opérateur (UI) | `src/main/resources/static/index.html` — page Aide `#help` (ADR-027) |
| Démarrage dev | `README.md` |
| Règles agent | `.cursor/rules.md` + `.cursor/rules/*.mdc` |
| Catalogue des agents | `../README.md` (`AGENTS/`) |

Pas de `docs/user/` tant qu’il n’y a pas d’utilisateur métier distinct de l’opérateur.

## Règle d’écriture

- Français ; identifiants techniques en anglais.  
- Distinguer spécifié vs implémenté.  
- Ne jamais supprimer une info : amender ou `obsolete`.  
- Livraison = `changelog` + `current_state` dans le même chantier.  
- Changement UI / fonction = **Aide `#help`** dans le même chantier (ADR-027).
