import { Injectable, Pipe, PipeTransform, signal } from '@angular/core';

export type Lang = 'en' | 'fr';

/** Traductions indépendantes du code (directive §15). Clé absente → la clé elle-même (visible en recette). */
const DICT: Record<Lang, Record<string, string>> = {
  en: {
    'app.title': 'VPS Autopilot', 'nav.servers': 'Servers', 'nav.operations': 'Operations', 'nav.members': 'Members',
    'nav.tokens': 'API tokens', 'nav.support': 'Managed & support', 'nav.billing': 'Billing', 'nav.audit': 'Audit log',
    'nav.logout': 'Sign out', 'auth.login': 'Sign in', 'auth.register': 'Create an account', 'auth.email': 'Email',
    'auth.password': 'Password', 'auth.totp': 'Two-factor code', 'auth.org': 'Organization name', 'auth.name': 'Your name',
    'auth.noAccount': 'No account yet?', 'auth.haveAccount': 'Already registered?', 'auth.currency': 'Billing currency',
    'auth.loginLede': 'Access your organization workspace.',
    'auth.registerLede': 'Start a trial workspace for your team.',
    'auth.registerLedeApproval': 'Submit your details. INOVNE validates the request; you receive login credentials by email.',
    'auth.backHome': 'Home',
    'auth.back': 'Back',
    'auth.asideLogin': 'Operations stay behind your explicit approval — same console as on the marketing site.',
    'auth.asideRegisterTitle': 'What we need',
    'auth.asideRegister': 'Enough to create your organization and contact you. No password yet.',
    'auth.fieldName': 'Your name (contact)',
    'auth.fieldEmail': 'Professional email',
    'auth.fieldOrg': 'Organization or project name',
    'auth.message': 'Context (optional)',
    'auth.messagePh': 'Servers, timeline, company…',
    'auth.noPasswordYet': 'Password is generated and emailed after INOVNE approval.',
    'auth.requestAccess': 'Request access',
    'auth.submitRequest': 'Send request',
    'auth.requestSentTitle': 'Request received',
    'auth.requestSentBody': 'Thank you. You will receive an email once INOVNE has validated your access.',
    'plat.signups': 'Pending signup requests',
    'plat.approve': 'Approve & send credentials',
    'plat.reject': 'Reject',
    'home.tagline': 'Your servers, under control.',
    'home.lede': 'Analyze, harden, deploy and prove backups — always with a plan and your approval.',
    'home.openConsole': 'Open console', 'home.seeSolutions': 'See solutions',
    'home.navMethod': 'Method', 'home.navSolutions': 'Solutions', 'home.navCapabilities': 'In the console',
    'home.heroStep1K': 'Analyze', 'home.heroStep1T': 'Read only', 'home.heroStep1E': 'OS · ports · hygiene',
    'home.heroStep2K': 'Plan', 'home.heroStep2T': 'Confirmed CHANGE', 'home.heroStep2E': 'Nothing without your OK',
    'home.heroStep3K': 'Prove', 'home.heroStep3T': 'Restore exercised', 'home.heroStep3E': 'Monthly drill',
    'home.solutionsTitle': 'Three offers, one method',
    'home.solutionsLede': 'Same tool, same discipline: analyze → propose → confirm → run → prove.',
    'home.sol1Title': 'Audit', 'home.sol1Body': 'Read-only review. Graded A–E report with evidence and recommendations. No changes.',
    'home.sol2Title': 'Go-live', 'home.sol2Body': 'Hardening, containers, HTTPS, Git deploy, data restore, encrypted daily backups.',
    'home.sol3Title': 'Maintenance', 'home.sol3Body': 'Verified backups, monthly restore drill, e-mail alerts, on-demand deploys, report.',
    'home.riskTitle': 'Three risk levels',
    'home.riskLede': 'Every action is classified. Destructive work is refused by design.',
    'home.risk1Title': 'READ', 'home.risk1Body': 'OS, CPU, RAM, ports, software. Automatic. No writes.',
    'home.risk2Title': 'CHANGE', 'home.risk2Body': 'Security, Docker, Traefik, database, deploy, alerts. Plan shown, confirmation required.',
    'home.risk3Title': 'DESTRUCTIVE', 'home.risk3Body': 'Reinstall, mass delete, drop: refused. No free root access for an AI.',
    'home.capTitle': 'What you manage once signed in',
    'home.capLede': 'Same chrome, same vocabulary — the console continues the story of this page.',
    'home.cap1Title': 'Servers', 'home.cap1Body': 'Hosts, SSH trust, analysis grade, deploy specs for apps and platforms.',
    'home.cap2Title': 'Operations', 'home.cap2Body': 'Proposed plans with hash, approve or reject, execution trail and output.',
    'home.cap3Title': 'Org & security', 'home.cap3Body': 'Members, API tokens, billing, audit chain, MFA for staff.',
    'home.ctaTitle': 'Ready to run a server?',
    'home.ctaLede': 'Secure sign-in for authorized accounts. Every CHANGE stays behind your approval.',
    'home.foot': 'By INOVNE — operations with approval, never blind scripts.',
    'common.save': 'Save', 'common.cancel': 'Cancel', 'common.add': 'Add', 'common.refresh': 'Refresh', 'common.delete': 'Delete',
    'common.status': 'Status', 'common.created': 'Created', 'common.none': 'Nothing yet.', 'common.loading': 'Loading…',
    'servers.add': 'Add a server', 'servers.name': 'Name', 'servers.host': 'Host', 'servers.port': 'SSH port',
    'servers.user': 'SSH user', 'servers.state': 'Connection', 'servers.grade': 'Audit',
    'server.trust': 'Host key', 'server.scan': 'Read host key', 'server.trustBtn': 'I checked it: trust this key',
    'server.trusted': 'Trusted key', 'server.credentials': 'SSH key', 'server.generate': 'Generate a key',
    'server.pubHint': 'Add this public key to ~/.ssh/authorized_keys of the SSH user on the server.',
    'server.discover': 'Analyze (read only)', 'server.snapshot': 'Latest analysis', 'server.propose': 'Propose an operation',
    'server.reason': 'Reason', 'server.recommend': 'Get recommendations', 'server.draft': 'Create draft',
    'deploy.title': 'Deployments', 'deploy.apps': 'Applications (1:1)', 'deploy.platforms': 'Platforms (N services)',
    'deploy.addApp': 'Register an app', 'deploy.addPlatform': 'Register a platform',
    'deploy.slug': 'Id', 'deploy.git': 'Git HTTPS URL', 'deploy.ref': 'Branch', 'deploy.hostname': 'Public hostname',
    'deploy.port': 'Container port', 'deploy.domain': 'Base domain', 'deploy.routing': 'Routing',
    'deploy.routingHost': 'Subdomains', 'deploy.routingPath': 'Paths on one host',
    'deploy.serviceId': 'Service id', 'deploy.template': 'Dockerfile template', 'deploy.path': 'URL path',
    'deploy.httpsOnly': 'HTTPS Git works out of the box. For private SSH repos, register a Deploy key below first.',
    'deploy.proposeApp': 'Propose deploy', 'deploy.proposePlatform': 'Propose platform deploy',
    'deploy.proposeService': 'Propose single-service deploy', 'deploy.delete': 'Remove',
    'deploy.saved': 'Deployment saved.', 'deploy.removed': 'Deployment removed.',
    'git.title': 'Git Deploy keys', 'git.add': 'Generate Deploy key', 'git.url': 'git@ URL',
    'git.hint': 'Add the public key as a read-only Deploy key on the repository, then propose deploy.',
    'git.created': 'Deploy key created.',
    'sql.title': 'Platform SQL', 'sql.hint': 'Read runs immediately; write needs approval. MariaDB required on the platform.',
    'sql.runRead': 'Run read', 'sql.proposeWrite': 'Propose write', 'sql.placeholder': 'SELECT …',
    'restore.title': 'Restore platform data', 'restore.hint': 'Upload a dump and/or archive, then propose restore (approval required).',
    'restore.sql': 'SQL dump (.sql / .sql.gz)', 'restore.uploads': 'Files archive', 'restore.propose': 'Propose restore',
    'secret.title': 'Runtime secrets', 'secret.hint': 'Value is stored one-shot and never shown again in operations.',
    'secret.name': 'Variable name', 'secret.value': 'Value', 'secret.proposeSet': 'Propose set secret',
    'secret.proposeUnset': 'Propose unset', 'secret.staged': 'Secret staged.',
    'ops.type': 'Type', 'ops.risk': 'Risk', 'ops.plan': 'Plan', 'ops.hash': 'Plan hash', 'ops.approve': 'Approve this exact plan',
    'ops.reject': 'Reject', 'ops.cancel': 'Cancel', 'ops.submit': 'Propose', 'ops.executions': 'Executions',
    'ops.output': 'Output', 'members.invite': 'Invite', 'members.role': 'Role', 'tokens.create': 'Create token',
    'tokens.scopes': 'Scopes', 'tokens.days': 'Valid (days)', 'tokens.secret': 'Copy this secret now, it will not be shown again:',
    'support.grant': 'Grant support access', 'support.staff': 'INOVNE technician email', 'support.level': 'Level',
    'support.hours': 'Duration (hours)', 'support.reason': 'Reason', 'support.revoke': 'Revoke',
    'support.interventions': 'Interventions', 'support.request': 'Request an intervention', 'support.title': 'Title',
    'billing.plan': 'Plan', 'billing.usage': 'Usage', 'billing.change': 'Choose this plan', 'billing.invoices': 'Invoices',
    'audit.verify': 'Verify integrity', 'audit.valid': 'Chain intact', 'audit.broken': 'Chain broken at #',
    'nav.security': 'Security', 'nav.platform': 'INOVNE back office', 'sec.mfa': 'Two-factor authentication',
    'sec.mfaOn': 'Two-factor authentication is on', 'sec.mfaOff': 'Two-factor authentication is off.',
    'sec.start': 'Set up two-factor authentication', 'sec.scan': 'Add this secret to your authenticator app, then enter the 6-digit code:',
    'sec.enable': 'Enable', 'sec.disable': 'Disable two-factor authentication', 'sec.password': 'Password',
    'sec.current': 'Current password', 'sec.new': 'New password (12 characters min.)', 'sec.changed': 'Password changed.',
    'sec.staffPending': 'Your INOVNE staff role stays inactive until two-factor authentication is enabled:',
    'plat.orgs': 'Organizations (metadata only)', 'plat.staff': 'INOVNE staff', 'plat.suspend': 'Suspend',
    'plat.reactivate': 'Reactivate', 'plat.inert': 'inactive (no MFA)',
  },
  fr: {
    'app.title': 'VPS Autopilot', 'nav.servers': 'Serveurs', 'nav.operations': 'Opérations', 'nav.members': 'Membres',
    'nav.tokens': 'Jetons d’API', 'nav.support': 'Managed & support', 'nav.billing': 'Facturation', 'nav.audit': 'Journal d’audit',
    'nav.logout': 'Se déconnecter', 'auth.login': 'Se connecter', 'auth.register': 'Créer un compte', 'auth.email': 'E-mail',
    'auth.password': 'Mot de passe', 'auth.totp': 'Code à deux facteurs', 'auth.org': 'Nom de l’organisation',
    'auth.name': 'Votre nom', 'auth.noAccount': 'Pas encore de compte ?', 'auth.haveAccount': 'Déjà inscrit ?',
    'auth.currency': 'Devise de facturation',
    'auth.loginLede': 'Accédez à l’espace de votre organisation.',
    'auth.registerLede': 'Ouvrez un espace d’essai pour votre équipe.',
    'auth.registerLedeApproval': 'Indiquez vos coordonnées. INOVNE valide la demande ; les identifiants partent par e-mail.',
    'auth.backHome': 'Accueil',
    'auth.back': 'Retour',
    'auth.asideLogin': 'Les opérations restent derrière votre accord — la même console que sur le site public.',
    'auth.asideRegisterTitle': 'Ce qu’il nous faut',
    'auth.asideRegister': 'Assez pour créer votre organisation et vous joindre. Pas de mot de passe pour l’instant.',
    'auth.fieldName': 'Votre nom (contact)',
    'auth.fieldEmail': 'E-mail professionnel',
    'auth.fieldOrg': 'Nom d’organisation ou de projet',
    'auth.message': 'Contexte (facultatif)',
    'auth.messagePh': 'Serveurs, échéance, société…',
    'auth.noPasswordYet': 'Le mot de passe est généré et envoyé par e-mail après validation INOVNE.',
    'auth.requestAccess': 'Demander un accès',
    'auth.submitRequest': 'Envoyer la demande',
    'auth.requestSentTitle': 'Demande reçue',
    'auth.requestSentBody': 'Merci. Vous recevrez un e-mail dès qu’INOVNE aura validé votre accès.',
    'plat.signups': 'Demandes d’inscription en attente',
    'plat.approve': 'Valider et envoyer les identifiants',
    'plat.reject': 'Refuser',
    'home.tagline': 'Vos serveurs, sous contrôle.',
    'home.lede': 'Analyser, sécuriser, déployer et prouver les sauvegardes — toujours avec un plan et votre accord.',
    'home.openConsole': 'Ouvrir la console', 'home.seeSolutions': 'Voir les solutions',
    'home.navMethod': 'Méthode', 'home.navSolutions': 'Solutions', 'home.navCapabilities': 'Dans la console',
    'home.heroStep1K': 'Analyse', 'home.heroStep1T': 'Lecture seule', 'home.heroStep1E': 'OS · ports · hygiène',
    'home.heroStep2K': 'Plan', 'home.heroStep2T': 'CHANGE confirmé', 'home.heroStep2E': 'Rien sans votre OK',
    'home.heroStep3K': 'Preuve', 'home.heroStep3T': 'Sauvegarde restaurée', 'home.heroStep3E': 'Exercice mensuel',
    'home.solutionsTitle': 'Trois offres, une méthode',
    'home.solutionsLede': 'Même outil, même discipline : analyser → proposer → confirmer → exécuter → prouver.',
    'home.sol1Title': 'Audit', 'home.sol1Body': 'Lecture seule. Rapport noté A–E : risques, preuves, recommandations. Aucune modification.',
    'home.sol2Title': 'Mise en ligne', 'home.sol2Body': 'Sécurisation, conteneurs, HTTPS, déploiement Git, reprise des données, sauvegardes chiffrées quotidiennes.',
    'home.sol3Title': 'Maintenance', 'home.sol3Body': 'Sauvegardes vérifiées, exercice de restauration mensuel, alertes e-mail, déploiements à la demande, rapport.',
    'home.riskTitle': 'Trois niveaux de risque',
    'home.riskLede': 'Chaque action est classée. Le destructif est refusé par conception.',
    'home.risk1Title': 'READ', 'home.risk1Body': 'OS, CPU, RAM, ports, logiciels. Automatique. Aucune écriture.',
    'home.risk2Title': 'CHANGE', 'home.risk2Body': 'Sécurité, Docker, Traefik, base, déploiement, alertes. Plan affiché, confirmation obligatoire.',
    'home.risk3Title': 'DESTRUCTIVE', 'home.risk3Body': 'Réinstall, suppression massive, drop : refusés. Jamais d’accès root libre à une IA.',
    'home.capTitle': 'Ce que vous pilotez une fois connecté',
    'home.capLede': 'Même chrome, même vocabulaire — la console continue cette page.',
    'home.cap1Title': 'Serveurs', 'home.cap1Body': 'Hôtes, confiance SSH, note d’analyse, fiches de déploiement apps et plateformes.',
    'home.cap2Title': 'Opérations', 'home.cap2Body': 'Plans proposés avec empreinte, approuver ou rejeter, journal d’exécution et sortie.',
    'home.cap3Title': 'Org & sécurité', 'home.cap3Body': 'Membres, jetons d’API, facturation, audit chaîné, MFA du personnel.',
    'home.ctaTitle': 'Prêt à piloter un serveur ?',
    'home.ctaLede': 'Connexion sécurisée pour les comptes autorisés. Chaque CHANGE reste derrière votre accord.',
    'home.foot': 'Par INOVNE — des opérations avec accord, jamais de scripts à l’aveugle.', 'common.save': 'Enregistrer', 'common.cancel': 'Annuler', 'common.add': 'Ajouter',
    'common.refresh': 'Actualiser', 'common.delete': 'Supprimer', 'common.status': 'Statut', 'common.created': 'Créé le',
    'common.none': 'Rien pour l’instant.', 'common.loading': 'Chargement…', 'servers.add': 'Ajouter un serveur',
    'servers.name': 'Nom', 'servers.host': 'Hôte', 'servers.port': 'Port SSH', 'servers.user': 'Utilisateur SSH',
    'servers.state': 'Connexion', 'servers.grade': 'Audit', 'server.trust': 'Clé d’hôte', 'server.scan': 'Lire la clé d’hôte',
    'server.trustBtn': 'J’ai vérifié : faire confiance', 'server.trusted': 'Clé approuvée', 'server.credentials': 'Clé SSH',
    'server.generate': 'Générer une clé',
    'server.pubHint': 'Ajoutez cette clé publique dans ~/.ssh/authorized_keys de l’utilisateur SSH sur le serveur.',
    'server.discover': 'Analyser (lecture seule)', 'server.snapshot': 'Dernière analyse', 'server.propose': 'Proposer une opération',
    'server.reason': 'Motif', 'server.recommend': 'Obtenir des recommandations', 'server.draft': 'Créer un brouillon',
    'deploy.title': 'Déploiements', 'deploy.apps': 'Applications (1:1)', 'deploy.platforms': 'Plateformes (N services)',
    'deploy.addApp': 'Enregistrer une app', 'deploy.addPlatform': 'Enregistrer une plateforme',
    'deploy.slug': 'Identifiant', 'deploy.git': 'URL Git HTTPS', 'deploy.ref': 'Branche', 'deploy.hostname': 'Nom d’hôte public',
    'deploy.port': 'Port conteneur', 'deploy.domain': 'Domaine de base', 'deploy.routing': 'Routage',
    'deploy.routingHost': 'Sous-domaines', 'deploy.routingPath': 'Chemins sur un hôte',
    'deploy.serviceId': 'Id du service', 'deploy.template': 'Modèle Dockerfile', 'deploy.path': 'Chemin URL',
    'deploy.httpsOnly': 'Git HTTPS fonctionne directement. Pour un dépôt SSH privé, enregistrez d’abord une Deploy key ci-dessous.',
    'deploy.proposeApp': 'Proposer le déploiement', 'deploy.proposePlatform': 'Proposer le déploiement plateforme',
    'deploy.proposeService': 'Proposer un service seul', 'deploy.delete': 'Retirer',
    'deploy.saved': 'Fiche enregistrée.', 'deploy.removed': 'Fiche retirée.',
    'git.title': 'Deploy keys Git', 'git.add': 'Générer une Deploy key', 'git.url': 'URL git@',
    'git.hint': 'Collez la clé publique en Deploy key lecture seule sur le dépôt, puis proposez le déploiement.',
    'git.created': 'Deploy key créée.',
    'sql.title': 'SQL plateforme', 'sql.hint': 'Lecture immédiate ; écriture sous approbation. MariaDB requis sur la plateforme.',
    'sql.runRead': 'Exécuter (lecture)', 'sql.proposeWrite': 'Proposer écriture', 'sql.placeholder': 'SELECT …',
    'restore.title': 'Restaurer les données', 'restore.hint': 'Déposez un dump et/ou une archive, puis proposez (approbation requise).',
    'restore.sql': 'Dump SQL (.sql / .sql.gz)', 'restore.uploads': 'Archive fichiers', 'restore.propose': 'Proposer restore',
    'secret.title': 'Secrets d’exécution', 'secret.hint': 'La valeur est stockée one-shot et n’apparaît jamais dans l’opération.',
    'secret.name': 'Nom de variable', 'secret.value': 'Valeur', 'secret.proposeSet': 'Proposer pose du secret',
    'secret.proposeUnset': 'Proposer retrait', 'secret.staged': 'Secret déposé.',
    'ops.type': 'Type', 'ops.risk': 'Risque', 'ops.plan': 'Plan', 'ops.hash': 'Empreinte du plan',
    'ops.approve': 'Approuver ce plan exact', 'ops.reject': 'Rejeter', 'ops.cancel': 'Annuler', 'ops.submit': 'Proposer',
    'ops.executions': 'Exécutions', 'ops.output': 'Sortie', 'members.invite': 'Inviter', 'members.role': 'Rôle',
    'tokens.create': 'Créer un jeton', 'tokens.scopes': 'Scopes', 'tokens.days': 'Validité (jours)',
    'tokens.secret': 'Copiez ce secret maintenant, il ne sera plus affiché :', 'support.grant': 'Accorder un accès support',
    'support.staff': 'E-mail du technicien INOVNE', 'support.level': 'Niveau', 'support.hours': 'Durée (heures)',
    'support.reason': 'Motif', 'support.revoke': 'Révoquer', 'support.interventions': 'Interventions',
    'support.request': 'Demander une intervention', 'support.title': 'Titre', 'billing.plan': 'Offre',
    'billing.usage': 'Utilisation', 'billing.change': 'Choisir cette offre', 'billing.invoices': 'Factures',
    'audit.verify': 'Vérifier l’intégrité', 'audit.valid': 'Chaîne intacte', 'audit.broken': 'Chaîne rompue au n°',
    'nav.security': 'Sécurité', 'nav.platform': 'Back-office INOVNE', 'sec.mfa': 'Double authentification',
    'sec.mfaOn': 'Double authentification active', 'sec.mfaOff': 'Double authentification désactivée.',
    'sec.start': 'Configurer la double authentification',
    'sec.scan': 'Ajoutez ce secret dans votre application d’authentification, puis saisissez le code à 6 chiffres :',
    'sec.enable': 'Activer', 'sec.disable': 'Désactiver la double authentification', 'sec.password': 'Mot de passe',
    'sec.current': 'Mot de passe actuel', 'sec.new': 'Nouveau mot de passe (12 caractères min.)',
    'sec.changed': 'Mot de passe modifié.',
    'sec.staffPending': 'Votre rôle de personnel INOVNE reste inactif tant que la double authentification n’est pas activée :',
    'plat.orgs': 'Organisations (métadonnées seulement)', 'plat.staff': 'Personnel INOVNE', 'plat.suspend': 'Suspendre',
    'plat.reactivate': 'Réactiver', 'plat.inert': 'inactif (sans MFA)',
  },
};

@Injectable({ providedIn: 'root' })
export class I18n {
  readonly lang = signal<Lang>(I18n.initial());

  private static initial(): Lang {
    try {
      const saved = localStorage.getItem('ap.lang');
      if (saved === 'fr' || saved === 'en') return saved;
    } catch { /* stockage indisponible */ }
    return navigator.language?.toLowerCase().startsWith('fr') ? 'fr' : 'en';
  }

  set(lang: Lang) {
    this.lang.set(lang);
    try { localStorage.setItem('ap.lang', lang); } catch { /* ignore */ }
    document.documentElement.lang = lang;
  }

  t(key: string): string {
    return DICT[this.lang()][key] ?? DICT.en[key] ?? key;
  }

  date(value?: string | null): string {
    return value ? new Intl.DateTimeFormat(this.lang(), { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
  }

  money(minor: number, currency: string): string {
    const digits = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2;
    return new Intl.NumberFormat(this.lang(), { style: 'currency', currency }).format(minor / Math.pow(10, digits));
  }
}

@Pipe({ name: 't', pure: false })
export class TPipe implements PipeTransform {
  constructor(private readonly i18n: I18n) {}
  transform(key: string): string {
    return this.i18n.t(key);
  }
}
