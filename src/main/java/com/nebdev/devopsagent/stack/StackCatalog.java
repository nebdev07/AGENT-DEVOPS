package com.nebdev.devopsagent.stack;

import java.util.List;

/**
 * Catalogue déterministe. L’install réelle reste versionnée (rôles bash, plus tard Ansible).
 */
public final class StackCatalog {

    private StackCatalog() {}

    public static List<StackComponent> all() {
        return List.of(security(), docker(), traefik(), mariadb(), deploy(), redis(), backups());
    }

    /** Jamais proposé à l’install. La discovery peut encore le détecter sur l’hôte. */
    public static List<RefusedOffer> notOffered() {
        return List.of(new RefusedOffer(
                "nginx",
                "Nginx sur l’hôte",
                "Ce n’est pas une option : un Nginx (ou Apache) qui sert du PHP depuis l’hôte "
                        + "est le schéma classique webshell puis miner. Reverse-proxy prévu : Traefik dans Docker.",
                "Si Nginx est déjà sur le VPS, l’analyse l’affiche en alerte. On ne propose pas de case à cocher."
        ));
    }

    public record RefusedOffer(String id, String label, String why, String what) {}

    private static StackComponent security() {
        return new StackComponent(
                "security",
                "Sécurité",
                "V1",
                "Baseline avant toute application.",
                "Un VPS livré avec mot de passe SSH et ports ouverts se fait scanner en quelques minutes : "
                        + "brute-force, puis souvent miner ou webshell. "
                        + "On durcit d’abord, on n’expose aucune app ensuite.",
                "Selon les cases cochées : pare-feu (22 seulement), fail2ban, SSH clés seulement "
                        + "(plus de mot de passe, root fermé), mises à jour auto. Ne change pas le port SSH. "
                        + "N’ouvre pas 80/443. Ne crée pas de user deploy.",
                true,
                false,
                StackComponent.PolicyImpact.POLICY_BREAK,
                "Sans durcissement, le VPS reste exposé (brute-force, ports ouverts). La politique exige la sécurité avant les apps.",
                "",
                List.of(),
                List.of(
                        new StackDetail(
                                "ufw",
                                "Pare-feu UFW",
                                "Tout ce qui écoute sur Internet peut être scanné. Sans pare-feu, un service oublié "
                                        + "(PHP, panel, miner) devient une porte. C’est comme ça qu’un VPS devient une poubelle.",
                                "Refuse tout le trafic entrant sauf SSH (port 22 / OpenSSH). N’ouvre pas 80 ni 443. Idempotent.",
                                true,
                                StackComponent.PolicyImpact.POLICY_BREAK,
                                "Sans UFW, un service lancé par erreur (ou par un pirate) est joignable de l’extérieur."
                        ),
                        new StackDetail(
                                "fail2ban",
                                "fail2ban (jail SSH)",
                                "Les bots tapent des milliers de mots de passe. Même avec des clés, les tentatives saturent les logs "
                                        + "et préparent d’autres attaques.",
                                "Installe fail2ban et active la jail sshd : après quelques échecs, l’IP est bannie un moment.",
                                true,
                                StackComponent.PolicyImpact.POLICY_BREAK,
                                "Sans fail2ban, le brute-force SSH n’est pas ralenti."
                        ),
                        new StackDetail(
                                "ssh-harden",
                                "SSH durci (clés seulement)",
                                "Le mot de passe envoyé par l’hébergeur et un root ouvert, c’est la porte d’entrée classique. "
                                        + "Une fois dedans : miner, webshell, persistance.",
                                "Drop-in sshd : PasswordAuthentication no, root fermé, MaxAuthTries 3, AllowUsers = ton compte actuel. "
                                        + "Exige une clé déjà dans authorized_keys (sinon lockout). Port 22 inchangé.",
                                true,
                                StackComponent.PolicyImpact.POLICY_BREAK,
                                "SSH encore en mot de passe / root ouvert = brute-force et prise de contrôle."
                        ),
                        new StackDetail(
                                "unattended",
                                "Mises à jour de sécurité",
                                "Un noyau ou OpenSSH non patché se fait exploiter sans que personne ne se connecte. "
                                        + "Les mines et webshells arrivent souvent après une CVE connue.",
                                "Installe unattended-upgrades et active les mises à jour de sécurité automatiques.",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans mises à jour auto, une faille publiée reste ouverte jusqu’à un apt manuel."
                        )
                )
        );
    }

    private static StackComponent docker() {
        return new StackComponent(
                "docker",
                "Docker + réseaux",
                "V1",
                "Isolation. Pas d’install à la main sur l’hôte.",
                "Apache, PHP, Tomcat ou PM2 installés directement sur l’hôte partagent le même système. "
                        + "Docker isole chaque app : un projet cassé ou compromis ne réécrit pas tout le serveur.",
                "Installe le moteur Docker (et Compose si coché), démarre le service, ajoute l’utilisateur SSH au groupe docker. "
                        + "Ne lance pas encore Traefik ni de base.",
                true,
                false,
                StackComponent.PolicyImpact.POLICY_BREAK,
                "Sans Docker : pas d’isolation, pas de Traefik, et l’install manuelle Apache/Tomcat/PM2 est interdite.",
                "",
                List.of(),
                List.of(
                        new StackDetail(
                                "engine",
                                "Moteur Docker",
                                "Sans moteur, tout finit installé sur l’hôte (APT, binaires, webroot) — exactement le terrain des webshells.",
                                "apt : docker.io, service docker activé.",
                                true,
                                StackComponent.PolicyImpact.POLICY_BREAK,
                                "Sans moteur Docker, le reste de la pile (Traefik, bases) n’a pas d’isolation."
                        ),
                        new StackDetail(
                                "compose",
                                "Docker Compose",
                                "Une pile déclarée (YAML) se recrée à l’identique. Un `docker run` à la main se perd et se recopie mal.",
                                "Installe docker-compose-v2 (ou docker-compose). N’écrit pas encore de compose d’app.",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans Compose, les stacks Traefik/MariaDB seront plus difficiles à versionner."
                        ),
                        new StackDetail(
                                "docker-group",
                                "Utilisateur SSH dans le groupe docker",
                                "Évite de tout faire en root. Le groupe docker reste puissant (équivalent root) : on ne l’offre qu’au compte de l’agent.",
                                "usermod -aG docker sur l’utilisateur SSH actuel. Il faudra parfois se reconnecter pour que le groupe prenne.",
                                true,
                                StackComponent.PolicyImpact.NONE,
                                ""
                        )
                )
        );
    }

    private static StackComponent traefik() {
        return new StackComponent(
                "traefik",
                "Traefik + HTTPS",
                "V2",
                "Seul reverse-proxy public prévu.",
                "Un seul point d’entrée public (80/443), HTTPS automatique, les apps restent dans Docker. "
                        + "Nginx/Apache sur l’hôte avec un dossier PHP, c’est le modèle compromis.",
                "Compose Traefik + réseau Docker proxy. UFW 80 (et 443 si HTTPS). "
                        + "Aucune app n’écoute tant qu’un routeur n’est pas déclaré. Exige Sécurité SKIP et Docker.",
                true,
                false,
                StackComponent.PolicyImpact.POLICY_BREAK,
                "Sans Traefik : apps exposées en ports bruts, pas d’HTTPS unique — le modèle Apache/hôte à éviter.",
                "",
                List.of("docker"),
                List.of(
                        new StackDetail(
                                "proxy",
                                "Reverse-proxy Traefik",
                                "Sans proxy unique, chaque app ouvre son port. Inmanageable, et un oubli expose une base.",
                                "Conteneur Traefik, réseau proxy, port 80. exposedByDefault=false. Pas de dashboard public.",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans proxy, les apps devront exposer des ports bruts."
                        ),
                        new StackDetail(
                                "https",
                                "HTTPS (Let’s Encrypt)",
                                "HTTP clair se fait intercepter et les navigateurs bloquent. Un certificat par domaine, géré au proxy.",
                                "Entrée 443 + challenge HTTP Let’s Encrypt. Les certificats arrivent quand un domaine sera déclaré (déploiement d’app).",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans HTTPS, le trafic app circule en clair."
                        )
                )
        );
    }

    private static StackComponent mariadb() {
        return new StackComponent(
                "mariadb",
                "MariaDB",
                "V2",
                "Une base par projet, pas une BD fourre-tout.",
                "Une seule base partagée entre projets, c’est un incident qui les emporte tous. "
                        + "Chaque application aura sa base et son utilisateur.",
                "Conteneur MariaDB, réseau Docker interne data, aucun port public. "
                        + "Mot de passe root uniquement sur le VPS. Grants par projet au déploiement. "
                        + "Exige Sécurité SKIP et Docker.",
                true,
                false,
                StackComponent.PolicyImpact.WARNING,
                "Pas de base isolée. Les projets SQL ne pourront pas être provisionnés proprement ici.",
                "",
                List.of("docker"),
                List.of(
                        new StackDetail(
                                "instance",
                                "Instance MariaDB (conteneur)",
                                "Une base sur l’hôte (apt mariadb-server) est plus dure à isoler et à sauvegarder proprement.",
                                "Un conteneur, un volume, pas d’écoute publique (pas de 3306 sur Internet).",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans instance, pas de SQL provisionné par l’agent."
                        ),
                        new StackDetail(
                                "per-project",
                                "Une base / un user par projet",
                                "Root SQL unique + toutes les apps dessus = un dump ou un ransomware les prend toutes.",
                                "Les grants (une base, un user) se feront au déploiement de chaque app, pas à l’install du serveur SQL.",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans isolation par projet, on retombe sur une BD fourre-tout."
                        )
                )
        );
    }

    private static StackComponent deploy() {
        return new StackComponent(
                "deploy",
                "Compte deploy",
                "V3",
                "Exécuter les apps sans root.",
                "Lancer les conteneurs avec le compte SSH actuel (groupe docker) revient à root. "
                        + "Un compte dédié, sans sudo, limite ce que peut faire un déploiement.",
                "Crée l’utilisateur deploy, l’ajoute au groupe docker, verrouille le mot de passe, "
                        + "copie la clé de l’agent, autorise SSH (AllowUsers) sans rouvrir le mot de passe. "
                        + "Aucune app n’est déployée. Exige Sécurité SKIP et Docker.",
                true,
                false,
                StackComponent.PolicyImpact.WARNING,
                "Sans compte deploy, les apps seraient lancées avec le compte SSH actuel (proche root via Docker).",
                "",
                List.of("docker"),
                List.of(
                        new StackDetail(
                                "user",
                                "Utilisateur deploy (docker, pas sudo)",
                                "Un déploiement en root, c’est toute la machine si l’image ou le compose est mauvais.",
                                "useradd deploy, groupe docker, passwd -l, pas de sudoers.",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans utilisateur dédié, docker run se fait avec le compte d’infra."
                        ),
                        new StackDetail(
                                "keys",
                                "Clé de l’agent pour deploy",
                                "Sans clé, le compte existe mais l’agent ne pourra pas s’y connecter plus tard.",
                                "Copie les lignes authorized_keys du compte actuel vers ~deploy/.ssh/.",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans clé, le compte deploy n’est pas joignable en SSH."
                        ),
                        new StackDetail(
                                "ssh-allow",
                                "SSH : AllowUsers inclut deploy",
                                "Le durcissement SSH limite les comptes. Oublier deploy = clé posée mais connexion refusée.",
                                "Ajoute deploy à AllowUsers du drop-in existant. Ne réouvre pas le mot de passe.",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans AllowUsers, SSH refusera le compte deploy."
                        )
                )
        );
    }

    private static StackComponent redis() {
        return new StackComponent(
                "redis",
                "Redis",
                "V2",
                "Seulement si un projet en a besoin.",
                "Redis ouvert sur 0.0.0.0 sans mot de passe se fait vider ou miner en quelques minutes. "
                        + "On ne l’installe que si un projet le demande, et seulement en interne.",
                "Conteneur Redis, réseau Docker data, requirepass, pas de port public. Optionnel.",
                false,
                false,
                StackComponent.PolicyImpact.NONE,
                "",
                "",
                List.of("docker"),
                List.of(
                        new StackDetail(
                                "internal",
                                "Redis interne (pas public)",
                                "Un Redis public = store vidé, parfois miner. Jamais de bind 0.0.0.0 sur l’hôte.",
                                "Conteneur sur le réseau Docker data. Mot de passe requirepass hors git sur le VPS.",
                                true,
                                StackComponent.PolicyImpact.NONE,
                                ""
                        )
                )
        );
    }

    private static StackComponent backups() {
        return new StackComponent(
                "backups",
                "Backups chiffrés 3-2-1",
                "V4",
                "Filet après compromission ou erreur.",
                "Sans copie hors-site, une compromission ou un disque perdu emporte tout. "
                        + "Un snapshot chez le même hébergeur ne suffit pas si le compte ou la machine part.",
                "Dump MariaDB chiffré sur le VPS chaque nuit (minuteur systemd, 14 conservés), copie vers le poste "
                        + "opérateur (state/backups/<serveur>/), lecture complète du dump. Passphrase dans "
                        + "state/backup.key (hors git) et sur le VPS (root seul).",
                true,
                false,
                StackComponent.PolicyImpact.POLICY_BREAK,
                "Sans backups, une compromission ou une erreur n’a pas de filet.",
                "",
                List.of(),
                List.of(
                        new StackDetail(
                                "encrypted-dump",
                                "Dump chiffré",
                                "Un dump en clair volé = toutes les données métier. Le chiffrement se fait avant de quitter le VPS.",
                                "mariadb-dump de toutes les bases via docker exec, refusé s’il est incomplet, gzip, "
                                        + "openssl AES-256-CBC, plaintext effacé ; archive tar chiffrée en flux des volumes "
                                        + "de fichiers (/app/data). Minuteur quotidien vers 02:30.",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans dump chiffré, une copie hors-site serait lisible si interceptée."
                        ),
                        new StackDetail(
                                "offsite",
                                "Copie hors-site",
                                "Le pirate qui a le VPS a aussi les backups locaux. Il faut une copie ailleurs.",
                                "L’agent télécharge le .enc (SFTP, SHA-256 contrôlé) vers state/backups/<serveur>/ sur le poste opérateur.",
                                true,
                                StackComponent.PolicyImpact.POLICY_BREAK,
                                "Backup seulement sur le VPS = plus de filet si la machine est prise."
                        ),
                        new StackDetail(
                                "restore-proof",
                                "Preuve de restauration",
                                "Un backup qui ne restaure pas n’existe pas. On le saurait trop tard, après l’incident.",
                                "Copie déchiffrée et lue en entier (marque de fin, tables par base, fichiers par volume) ; "
                                        + "exercice de restauration à la demande dans une base et un dossier jetables du VPS "
                                        + "(bande Sauvegardes).",
                                true,
                                StackComponent.PolicyImpact.WARNING,
                                "Sans test de restore, on découvre un backup mort le jour J."
                        )
                )
        );
    }

}
