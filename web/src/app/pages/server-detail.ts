import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api, Auth, Notice } from '../core/api';
import { I18n, TPipe } from '../core/i18n';

@Component({
  selector: 'ap-server-detail',
  imports: [FormsModule, TPipe],
  template: `
    @if (server(); as s) {
      <h1>{{ s.name }} <small class="muted">{{ s.sshUser }}&#64;{{ s.host }}:{{ s.sshPort }}</small></h1>

      <section class="card">
        <h2>{{ 'server.trust' | t }}</h2>
        @if (s.hostFingerprint) { <p>{{ 'server.trusted' | t }} : <code>{{ s.hostFingerprint }}</code></p> }
        @if (auth.can('server.manage')) {
          <button (click)="scan()">{{ 'server.scan' | t }}</button>
          @if (scanned()) {
            <p><code>{{ scanned() }}</code></p>
            <button (click)="trust()">{{ 'server.trustBtn' | t }}</button>
          }
        }
      </section>

      <section class="card">
        <h2>{{ 'server.credentials' | t }}</h2>
        @for (c of credentials(); track c.id) {
          <div class="cred"><span class="pill" [class]="c.status">{{ c.status }}</span> <code>{{ c.fingerprint }}</code>
            @if (c.status !== 'REVOKED') { <details><summary>{{ 'server.pubHint' | t }}</summary><pre>{{ c.publicKey }}</pre></details> }
          </div>
        } @empty { <p class="muted">{{ 'common.none' | t }}</p> }
        @if (auth.can('credential.manage')) { <button (click)="generate()">{{ 'server.generate' | t }}</button> }
      </section>

      <section class="card">
        <h2>{{ 'server.snapshot' | t }}</h2>
        @if (snapshot(); as snap) {
          <p><span class="grade">{{ snap.auditGrade }}</span> {{ snap.auditScore }}/100 — {{ i18n.date(snap.takenAt) }}</p>
        } @else { <p class="muted">{{ 'common.none' | t }}</p> }
        @if (auth.can('server.analyze')) { <button (click)="propose('DISCOVER')">{{ 'server.discover' | t }}</button> }
        @if (auth.can('ai.use') && snapshot()) { <button class="secondary" (click)="recommend()">{{ 'server.recommend' | t }}</button> }
        @if (recommendations(); as r) {
          <ul class="recs">
            @for (x of r.recommendations; track $index) {
              <li><span class="pill" [class]="x.risk">{{ x.risk }}</span> <strong>{{ x.title }}</strong> — {{ x.rationale }}
                @if (x.operationType && auth.can('operation.propose')) {
                  <button class="link" (click)="draft(r.interactionId, $index)">{{ 'server.draft' | t }} ({{ x.operationType }})</button>
                }
              </li>
            }
          </ul>
          <p class="muted">{{ r.provider }} · {{ r.model }}</p>
        }
      </section>

      @if (auth.can('credential.manage')) {
        <section class="card">
          <h2>{{ 'git.title' | t }}</h2>
          <p class="muted">{{ 'git.hint' | t }}</p>
          @for (g of gitKeys(); track g.id) {
            <div class="cred">
              <strong>{{ g.keyId }}</strong>
              <code>{{ g.fingerprint }}</code>
              @if (g.deployKeyPage) { <a [href]="g.deployKeyPage" target="_blank" rel="noopener">Deploy key</a> }
              <details><summary>public key</summary><pre>{{ g.publicKey }}</pre></details>
              <button class="link danger" (click)="removeGit(g.id)">{{ 'common.delete' | t }}</button>
            </div>
          } @empty { <p class="muted">{{ 'common.none' | t }}</p> }
          <form class="inline" (ngSubmit)="generateGit()">
            <input name="gitUrl" [placeholder]="'git.url' | t" [(ngModel)]="gitUrl" required style="min-width:18rem" />
            <button type="submit">{{ 'git.add' | t }}</button>
          </form>
        </section>
      }

      <section class="card">
        <h2>{{ 'deploy.title' | t }}</h2>
        <p class="muted">{{ 'deploy.httpsOnly' | t }}</p>

        <h3>{{ 'deploy.apps' | t }}</h3>
        @for (d of apps(); track d.id) {
          <div class="cred">
            <strong>{{ d.slug }}</strong>
            <span class="muted">{{ d.spec?.hostname }} · {{ d.spec?.gitUrl }}</span>
            @if (auth.can('operation.propose')) {
              <button class="link" (click)="proposeDeploy('DEPLOY_APP', d.id)">{{ 'deploy.proposeApp' | t }}</button>
            }
            @if (auth.can('server.manage')) {
              <button class="link danger" (click)="removeDeploy(d.id)">{{ 'deploy.delete' | t }}</button>
            }
          </div>
        } @empty { <p class="muted">{{ 'common.none' | t }}</p> }
        @if (auth.can('server.manage')) {
          <details>
            <summary>{{ 'deploy.addApp' | t }}</summary>
            <form class="stack" (ngSubmit)="saveApp()">
              <label>{{ 'deploy.slug' | t }} <input name="appSlug" [(ngModel)]="appForm.id" required /></label>
              <label>{{ 'deploy.git' | t }} <input name="appGit" [(ngModel)]="appForm.gitUrl" required placeholder="https://github.com/org/repo.git" /></label>
              <label>{{ 'deploy.ref' | t }} <input name="appRef" [(ngModel)]="appForm.gitRef" /></label>
              <label>{{ 'deploy.hostname' | t }} <input name="appHost" [(ngModel)]="appForm.hostname" required /></label>
              <label>{{ 'deploy.port' | t }} <input name="appPort" type="number" [(ngModel)]="appForm.port" /></label>
              <button type="submit">{{ 'common.save' | t }}</button>
            </form>
          </details>
        }

        <h3>{{ 'deploy.platforms' | t }}</h3>
        @for (d of platforms(); track d.id) {
          <div class="cred">
            <strong>{{ d.slug }}</strong>
            <span class="muted">{{ d.spec?.baseDomain }} · {{ (d.spec?.services ?? []).length }} svc</span>
            @if (auth.can('operation.propose')) {
              <button class="link" (click)="proposeDeploy('DEPLOY_PLATFORM', d.id)">{{ 'deploy.proposePlatform' | t }}</button>
            }
            @if (auth.can('server.manage')) {
              <button class="link danger" (click)="removeDeploy(d.id)">{{ 'deploy.delete' | t }}</button>
            }
            @if (auth.can('operation.propose') && d.spec?.services?.length) {
              <div class="inline">
                @for (svc of d.spec.services; track svc.id) {
                  <button class="secondary" (click)="proposeDeploy('DEPLOY_PLATFORM', d.id, svc.id)">
                    {{ 'deploy.proposeService' | t }} « {{ svc.id }} »
                  </button>
                }
              </div>
            }
          </div>
        } @empty { <p class="muted">{{ 'common.none' | t }}</p> }
        @if (auth.can('server.manage')) {
          <details>
            <summary>{{ 'deploy.addPlatform' | t }}</summary>
            <form class="stack" (ngSubmit)="savePlatform()">
              <label>{{ 'deploy.slug' | t }} <input name="platSlug" [(ngModel)]="platForm.id" required /></label>
              <label>{{ 'deploy.domain' | t }} <input name="platDomain" [(ngModel)]="platForm.baseDomain" required /></label>
              <label>{{ 'deploy.routing' | t }}
                <select name="platRouting" [(ngModel)]="platForm.routing">
                  <option value="host">{{ 'deploy.routingHost' | t }}</option>
                  <option value="path">{{ 'deploy.routingPath' | t }}</option>
                </select>
              </label>
              <label>{{ 'deploy.serviceId' | t }} <input name="svcId" [(ngModel)]="platForm.serviceId" required /></label>
              <label>{{ 'deploy.git' | t }} <input name="svcGit" [(ngModel)]="platForm.gitUrl" required /></label>
              <label>{{ 'deploy.ref' | t }} <input name="svcRef" [(ngModel)]="platForm.gitRef" /></label>
              <label>{{ 'deploy.hostname' | t }} <input name="svcHost" [(ngModel)]="platForm.hostname" /></label>
              <label>{{ 'deploy.port' | t }} <input name="svcPort" type="number" [(ngModel)]="platForm.port" /></label>
              <label>{{ 'deploy.template' | t }}
                <select name="svcTpl" [(ngModel)]="platForm.template">
                  <option value="next">next</option>
                  <option value="node">node</option>
                  <option value="spring-boot-21">spring-boot-21</option>
                  <option value="spring-boot-17">spring-boot-17</option>
                  <option value="spring-boot-11">spring-boot-11</option>
                  <option value="">(Dockerfile in repo)</option>
                </select>
              </label>
              <label>{{ 'deploy.path' | t }} <input name="svcPath" [(ngModel)]="platForm.pathPrefix" /></label>
              <button type="submit">{{ 'common.save' | t }}</button>
            </form>
          </details>
        }
      </section>

      @if (platforms().length && (auth.can('server.analyze') || auth.can('operation.propose'))) {
        <section class="card">
          <h2>{{ 'sql.title' | t }}</h2>
          <p class="muted">{{ 'sql.hint' | t }}</p>
          <form class="stack" (ngSubmit)="false">
            <label>{{ 'deploy.platforms' | t }}
              <select name="sqlPlat" [(ngModel)]="sqlSpecId">
                @for (d of platforms(); track d.id) { <option [value]="d.id">{{ d.slug }}</option> }
              </select>
            </label>
            <textarea name="sqlText" rows="4" [(ngModel)]="sqlText" [placeholder]="'sql.placeholder' | t"></textarea>
            <div class="inline">
              @if (auth.can('server.analyze')) {
                <button type="button" (click)="runSql('PLATFORM_SQL_READ')">{{ 'sql.runRead' | t }}</button>
              }
              @if (auth.can('operation.propose')) {
                <button type="button" class="secondary" (click)="runSql('PLATFORM_SQL_WRITE')">{{ 'sql.proposeWrite' | t }}</button>
              }
            </div>
          </form>
        </section>
      }

      @if (platforms().length && auth.can('operation.propose')) {
        <section class="card">
          <h2>{{ 'restore.title' | t }}</h2>
          <p class="muted">{{ 'restore.hint' | t }}</p>
          <form class="stack" (ngSubmit)="proposeRestore()">
            <label>{{ 'deploy.platforms' | t }}
              <select name="restPlat" [(ngModel)]="restoreSpecId">
                @for (d of platforms(); track d.id) { <option [value]="d.id">{{ d.slug }}</option> }
              </select>
            </label>
            <label>{{ 'restore.sql' | t }} <input name="restSql" type="file" (change)="onRestoreSql($event)" /></label>
            <label>{{ 'restore.uploads' | t }} <input name="restUp" type="file" (change)="onRestoreUploads($event)" /></label>
            <button type="submit">{{ 'restore.propose' | t }}</button>
          </form>
        </section>

        <section class="card">
          <h2>{{ 'secret.title' | t }}</h2>
          <p class="muted">{{ 'secret.hint' | t }}</p>
          <form class="stack" (ngSubmit)="proposeSetSecret()">
            <label>{{ 'deploy.platforms' | t }}
              <select name="secPlat" [(ngModel)]="secretSpecId" (ngModelChange)="onSecretPlatform()">
                @for (d of platforms(); track d.id) { <option [value]="d.id">{{ d.slug }}</option> }
              </select>
            </label>
            <label>{{ 'deploy.serviceId' | t }}
              <select name="secSvc" [(ngModel)]="secretServiceId">
                @for (svc of secretServices(); track svc.id) { <option [value]="svc.id">{{ svc.id }}</option> }
              </select>
            </label>
            <label>{{ 'secret.name' | t }} <input name="secName" [(ngModel)]="secretName" required /></label>
            <label>{{ 'secret.value' | t }} <input name="secVal" type="password" [(ngModel)]="secretValue" autocomplete="off" /></label>
            <div class="inline">
              <button type="submit">{{ 'secret.proposeSet' | t }}</button>
              <button type="button" class="secondary" (click)="proposeUnsetSecret()">{{ 'secret.proposeUnset' | t }}</button>
            </div>
          </form>
        </section>
      }

      @if (auth.can('operation.propose')) {
        <section class="card">
          <h2>{{ 'server.propose' | t }}</h2>
          <form class="inline" (ngSubmit)="propose(type, reason)">
            <select name="type" [(ngModel)]="type">
              @for (c of catalog(); track c.type) { @if (c.risk !== 'READ') { <option [value]="c.type">{{ c.label }}</option> } }
            </select>
            <input name="reason" [placeholder]="'server.reason' | t" [(ngModel)]="reason" />
            <button type="submit">{{ 'ops.submit' | t }}</button>
          </form>
        </section>
      }
    } @else { <p>{{ 'common.loading' | t }}</p> }
  `,
  styles: [`
    .stack { display: flex; flex-direction: column; gap: .4rem; max-width: 36rem; margin-top: .6rem; }
    .cred { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; margin-bottom: .5rem; }
    h3 { font-size: 1rem; margin: 1rem 0 .4rem; }
  `],
})
export class ServerDetailPage implements OnInit {
  readonly id = input.required<string>();
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notice = inject(Notice);
  protected readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);
  server = signal<any | null>(null);
  credentials = signal<any[]>([]);
  snapshot = signal<any | null>(null);
  scanned = signal<string | null>(null);
  catalog = signal<any[]>([]);
  deployments = signal<any[]>([]);
  gitKeys = signal<any[]>([]);
  recommendations = signal<any | null>(null);
  type = 'INSTALL_SECURITY';
  reason = '';
  gitUrl = '';
  sqlSpecId = '';
  sqlText = '';
  restoreSpecId = '';
  restoreSqlFile: File | null = null;
  restoreUploadsFile: File | null = null;
  secretSpecId = '';
  secretServiceId = '';
  secretName = '';
  secretValue = '';
  appForm = { id: '', gitUrl: '', gitRef: 'main', hostname: '', port: 8080 };
  platForm = {
    id: '', baseDomain: '', routing: 'host', serviceId: 'www', gitUrl: '', gitRef: 'main',
    hostname: '', port: 3000, template: 'next', pathPrefix: '/',
  };

  ngOnInit() { this.load(); }

  apps() { return this.deployments().filter(d => d.kind === 'APP'); }
  platforms() { return this.deployments().filter(d => d.kind === 'PLATFORM'); }
  secretServices(): { id: string }[] {
    const p = this.platforms().find(d => d.id === this.secretSpecId);
    return p?.spec?.services ?? [];
  }

  private base() { return `/servers/${this.id()}`; }
  private deployBase() { return `${this.base()}/deployments`; }

  async load() {
    this.server.set(await this.api.get(this.base()));
    this.credentials.set(await this.api.get(this.base() + '/credentials'));
    this.snapshot.set(await this.api.get(this.base() + '/snapshot'));
    this.catalog.set(await this.api.get('/operations/catalog'));
    this.deployments.set(await this.api.get(this.deployBase()));
    if (this.auth.can('credential.manage')) {
      this.gitKeys.set(await this.api.get('/git-credentials'));
    }
    const plats = this.platforms();
    if (plats.length) {
      if (!this.sqlSpecId) this.sqlSpecId = plats[0].id;
      if (!this.restoreSpecId) this.restoreSpecId = plats[0].id;
      if (!this.secretSpecId) {
        this.secretSpecId = plats[0].id;
        this.onSecretPlatform();
      }
    }
  }

  onSecretPlatform() {
    const svcs = this.secretServices();
    this.secretServiceId = svcs[0]?.id ?? '';
  }

  async runSql(type: 'PLATFORM_SQL_READ' | 'PLATFORM_SQL_WRITE') {
    await this.propose(type, type === 'PLATFORM_SQL_READ' ? 'SQL read' : 'SQL write', {
      specId: this.sqlSpecId,
      sql: this.sqlText,
    });
  }

  onRestoreSql(ev: Event) {
    const input = ev.target as HTMLInputElement;
    this.restoreSqlFile = input.files?.[0] ?? null;
  }

  onRestoreUploads(ev: Event) {
    const input = ev.target as HTMLInputElement;
    this.restoreUploadsFile = input.files?.[0] ?? null;
  }

  async proposeRestore() {
    const parameters: Record<string, unknown> = { specId: this.restoreSpecId };
    if (this.restoreSqlFile) {
      const form = new FormData();
      form.append('kind', 'SQL_DUMP');
      form.append('file', this.restoreSqlFile);
      const staged = await this.api.upload(`${this.base()}/staging/restore`, form);
      parameters['sqlStagingId'] = staged.id;
    }
    if (this.restoreUploadsFile) {
      const form = new FormData();
      form.append('kind', 'UPLOADS_ARCHIVE');
      form.append('file', this.restoreUploadsFile);
      const staged = await this.api.upload(`${this.base()}/staging/restore`, form);
      parameters['uploadsStagingId'] = staged.id;
    }
    await this.propose('RESTORE_PLATFORM', 'Restore platform', parameters);
  }

  async proposeSetSecret() {
    const staged = await this.api.post(`${this.base()}/staging/secrets`, {
      serviceId: this.secretServiceId,
      name: this.secretName,
      value: this.secretValue,
      restart: true,
    });
    this.notice.ok(this.i18n.t('secret.staged'));
    this.secretValue = '';
    await this.propose('SET_PLATFORM_SECRET', 'Set platform secret', {
      specId: this.secretSpecId,
      secretStagingId: staged.id,
    });
  }

  async proposeUnsetSecret() {
    await this.propose('UNSET_PLATFORM_SECRET', 'Unset platform secret', {
      specId: this.secretSpecId,
      serviceId: this.secretServiceId,
      secretName: this.secretName,
      restart: true,
    });
  }

  async generateGit() {
    await this.api.post('/git-credentials/generate', { gitUrl: this.gitUrl });
    this.notice.ok(this.i18n.t('git.created'));
    this.gitUrl = '';
    await this.load();
  }

  async removeGit(id: string) {
    await this.api.delete('/git-credentials/' + id);
    await this.load();
  }

  async scan() { this.scanned.set((await this.api.post(this.base() + '/host-key/scan')).fingerprint); }

  async trust() {
    await this.api.post(this.base() + '/host-key/trust', { fingerprint: this.scanned() });
    this.scanned.set(null);
    await this.load();
  }

  async generate() {
    await this.api.post(this.base() + '/credentials/generate');
    await this.load();
  }

  async propose(type: string, reason = '', parameters?: Record<string, unknown>) {
    const body: Record<string, unknown> = { serverId: this.id(), type, reason };
    if (parameters) body['parameters'] = parameters;
    const op = await this.api.post('/operations', body, { 'Idempotency-Key': crypto.randomUUID() });
    this.notice.ok(`${op.label} — ${op.status}`);
    this.router.navigate(['/operations'], { queryParams: { focus: op.id } });
  }

  async proposeDeploy(type: 'DEPLOY_APP' | 'DEPLOY_PLATFORM', specId: string, serviceId?: string) {
    const parameters: Record<string, unknown> = { specId };
    if (serviceId) parameters['serviceId'] = serviceId;
    await this.propose(type, type === 'DEPLOY_APP' ? 'Deploy app' : 'Deploy platform', parameters);
  }

  async saveApp() {
    await this.api.post(this.deployBase() + '/apps', { ...this.appForm });
    this.notice.ok(this.i18n.t('deploy.saved'));
    this.appForm = { id: '', gitUrl: '', gitRef: 'main', hostname: '', port: 8080 };
    await this.load();
  }

  async savePlatform() {
    const f = this.platForm;
    const service: Record<string, unknown> = {
      id: f.serviceId,
      gitUrl: f.gitUrl,
      gitRef: f.gitRef || 'main',
      port: f.port,
      needs: [],
      template: f.template || undefined,
      pathPrefix: f.routing === 'path' ? (f.pathPrefix || '/') : undefined,
    };
    if (f.hostname) service['hostname'] = f.hostname;
    await this.api.post(this.deployBase() + '/platforms', {
      id: f.id,
      baseDomain: f.baseDomain,
      routing: f.routing,
      services: [service],
      retainSources: true,
    });
    this.notice.ok(this.i18n.t('deploy.saved'));
    this.platForm = {
      id: '', baseDomain: '', routing: 'host', serviceId: 'www', gitUrl: '', gitRef: 'main',
      hostname: '', port: 3000, template: 'next', pathPrefix: '/',
    };
    await this.load();
  }

  async removeDeploy(specId: string) {
    await this.api.delete(`${this.deployBase()}/${specId}`);
    this.notice.ok(this.i18n.t('deploy.removed'));
    await this.load();
  }

  async recommend() { this.recommendations.set(await this.api.post(this.base() + '/recommendations?locale=' + this.i18n.lang())); }

  async draft(interactionId: string, index: number) {
    const op = await this.api.post(`/ai/interactions/${interactionId}/recommendations/${index}/draft`);
    this.router.navigate(['/operations'], { queryParams: { focus: op.id } });
  }
}
