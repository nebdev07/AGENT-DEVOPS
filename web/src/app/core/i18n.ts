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
    'home.tagline': 'Your servers, under control.',
    'home.lede': 'Analyze, harden, deploy and prove backups — always with a plan and your approval.',
    'home.openConsole': 'Open console',
    'home.navMethod': 'Method', 'home.navSolutions': 'Solutions',
    'home.methodTitle': 'One method, every change',
    'home.methodLede': 'Nothing runs on your VPS without a readable plan and an explicit OK.',
    'home.step1Title': 'Analyze', 'home.step1Body': 'Read-only: OS, ports, hygiene, grade A–E.',
    'home.step2Title': 'Plan', 'home.step2Body': 'A CHANGE you can read before anything is applied.',
    'home.step3Title': 'Prove', 'home.step3Body': 'Backups exercised monthly — restore is not a hope.',
    'home.solutionsTitle': 'Three offers, one discipline',
    'home.solutionsLede': 'Same tool for audit, go-live and managed operations.',
    'home.sol1Title': 'Audit', 'home.sol1Body': 'Read-only review of your server. Graded report, evidence, recommendations. No changes.',
    'home.sol2Title': 'Go-live', 'home.sol2Body': 'Secure baseline, Traefik, deploy apps and platforms with your confirmation at each step.',
    'home.sol3Title': 'Managed', 'home.sol3Body': 'INOVNE support with time-boxed access, interventions and an intact audit chain.',
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
    'home.tagline': 'Vos serveurs, sous contrôle.',
    'home.lede': 'Analyser, sécuriser, déployer et prouver les sauvegardes — toujours avec un plan et votre accord.',
    'home.openConsole': 'Ouvrir la console',
    'home.navMethod': 'Méthode', 'home.navSolutions': 'Solutions',
    'home.methodTitle': 'Une méthode, chaque changement',
    'home.methodLede': 'Rien ne s’exécute sur votre VPS sans un plan lisible et un OK explicite.',
    'home.step1Title': 'Analyser', 'home.step1Body': 'Lecture seule : OS, ports, hygiène, note A–E.',
    'home.step2Title': 'Planifier', 'home.step2Body': 'Un CHANGE que vous lisez avant toute application.',
    'home.step3Title': 'Prouver', 'home.step3Body': 'Sauvegardes exercées chaque mois — la restauration n’est pas un espoir.',
    'home.solutionsTitle': 'Trois offres, une discipline',
    'home.solutionsLede': 'Le même outil pour l’audit, la mise en ligne et le managed.',
    'home.sol1Title': 'Audit', 'home.sol1Body': 'Revue en lecture seule. Rapport noté, preuves, recommandations. Aucune modification.',
    'home.sol2Title': 'Mise en ligne', 'home.sol2Body': 'Baseline sécurité, Traefik, déploiement apps et plateformes avec confirmation à chaque étape.',
    'home.sol3Title': 'Managed', 'home.sol3Body': 'Support INOVNE avec accès borné dans le temps, interventions et journal d’audit intact.',
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
