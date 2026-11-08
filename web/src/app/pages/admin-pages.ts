import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, Auth } from '../core/api';
import { I18n, TPipe } from '../core/i18n';

const ROLES = ['ORGANIZATION_OWNER', 'ORGANIZATION_ADMIN', 'TECHNICAL_MANAGER', 'DEVELOPER', 'VIEWER', 'BILLING_MANAGER'];

@Component({
  selector: 'ap-members',
  imports: [FormsModule, TPipe],
  template: `
    <h1>{{ 'nav.members' | t }}</h1>
    @if (auth.can('member.manage')) {
      <form class="card inline" (ngSubmit)="invite()">
        <input name="email" type="email" [placeholder]="'auth.email' | t" [(ngModel)]="email" required />
        <select name="role" [(ngModel)]="role">@for (r of roles; track r) { <option [value]="r">{{ r }}</option> }</select>
        <button type="submit">{{ 'members.invite' | t }}</button>
      </form>
    }
    <table>
      <thead><tr><th>{{ 'auth.email' | t }}</th><th>{{ 'auth.name' | t }}</th><th>{{ 'members.role' | t }}</th><th></th></tr></thead>
      <tbody>
        @for (m of members(); track m.userId) {
          <tr><td>{{ m.email }}</td><td>{{ m.displayName }}</td>
            <td>@if (auth.can('member.manage')) {
              <select [value]="m.roleCode" (change)="setRole(m.userId, $any($event.target).value)">
                @for (r of roles; track r) { <option [value]="r">{{ r }}</option> }</select>
            } @else { {{ m.roleCode }} }</td>
            <td>@if (auth.can('member.manage')) { <button class="link danger" (click)="remove(m.userId)">{{ 'common.delete' | t }}</button> }</td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class MembersPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly auth = inject(Auth);
  roles = ROLES;
  members = signal<any[]>([]);
  email = '';
  role = 'DEVELOPER';
  ngOnInit() { this.load(); }
  async load() { this.members.set(await this.api.get('/members')); }
  async invite() { await this.api.post('/invitations', { email: this.email, role: this.role }); this.email = ''; }
  async setRole(userId: string, role: string) { await this.api.put(`/members/${userId}/role`, { role }).finally(() => this.load()); }
  async remove(userId: string) { await this.api.delete(`/members/${userId}`); await this.load(); }
}

@Component({
  selector: 'ap-tokens',
  imports: [FormsModule, TPipe],
  template: `
    <h1>{{ 'nav.tokens' | t }}</h1>
    <form class="card" (ngSubmit)="create()">
      <input name="name" placeholder="CI / monitoring" [(ngModel)]="name" required />
      <fieldset><legend>{{ 'tokens.scopes' | t }}</legend>
        @for (s of scopes; track s) { <label class="check"><input type="checkbox" [checked]="chosen.has(s)" (change)="toggle(s)" /> {{ s }}</label> }
      </fieldset>
      <label>{{ 'tokens.days' | t }} <input name="days" type="number" min="1" max="365" [(ngModel)]="days" /></label>
      <button type="submit">{{ 'tokens.create' | t }}</button>
    </form>
    @if (secret()) { <div class="card warn">{{ 'tokens.secret' | t }}<pre>{{ secret() }}</pre></div> }
    <table>
      <thead><tr><th>Name</th><th>Prefix</th><th>{{ 'tokens.scopes' | t }}</th><th>Expires</th><th></th></tr></thead>
      <tbody>@for (t of tokens(); track t.id) {
        <tr [class.muted]="t.revokedAt"><td>{{ t.name }}</td><td><code>{{ t.prefix }}…</code></td><td>{{ t.scopes.join(', ') }}</td>
          <td>{{ i18n.date(t.expiresAt) }}</td>
          <td>@if (!t.revokedAt) { <button class="link danger" (click)="revoke(t.id)">{{ 'support.revoke' | t }}</button> }</td></tr>
      }</tbody>
    </table>
  `,
})
export class TokensPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly i18n = inject(I18n);
  scopes = ['org.read', 'server.read', 'server.manage', 'server.analyze', 'operation.read', 'operation.propose',
    'operation.approve', 'audit.read', 'managed.read', 'ai.use'];
  chosen = new Set<string>(['server.read', 'operation.read']);
  tokens = signal<any[]>([]);
  secret = signal<string | null>(null);
  name = '';
  days = 90;
  ngOnInit() { this.load(); }
  toggle(s: string) { this.chosen.has(s) ? this.chosen.delete(s) : this.chosen.add(s); }
  async load() { this.tokens.set(await this.api.get('/api-tokens')); }
  async create() {
    const r = await this.api.post('/api-tokens', { name: this.name, scopes: [...this.chosen], days: this.days });
    this.secret.set(r.secret);
    await this.load();
  }
  async revoke(id: string) { await this.api.delete(`/api-tokens/${id}`); await this.load(); }
}

@Component({
  selector: 'ap-support',
  imports: [FormsModule, TPipe],
  template: `
    <h1>{{ 'nav.support' | t }}</h1>
    @for (c of contracts(); track c.id) {
      <p class="card">Contract <strong>{{ c.serviceLevel }}</strong> · {{ c.status }} · {{ c.responseTimeMinutes }} min</p>
    }
    @if (auth.can('support.grant')) {
      <form class="card inline" (ngSubmit)="grant()">
        <input name="staff" type="email" [placeholder]="'support.staff' | t" [(ngModel)]="g.staffEmail" required />
        <select name="level" [(ngModel)]="g.level"><option>READ</option><option>OPERATE</option></select>
        <input name="hours" type="number" min="1" max="720" [(ngModel)]="g.hours" />
        <input name="reason" [placeholder]="'support.reason' | t" [(ngModel)]="g.reason" required />
        <button type="submit">{{ 'support.grant' | t }}</button>
      </form>
    }
    <table>
      <thead><tr><th>{{ 'support.level' | t }}</th><th>{{ 'support.reason' | t }}</th><th>Expires</th><th>{{ 'common.status' | t }}</th><th></th></tr></thead>
      <tbody>@for (x of grants(); track x.id) {
        <tr><td>{{ x.accessLevel }}</td><td>{{ x.reason }}</td><td>{{ i18n.date(x.expiresAt) }}</td><td>{{ x.active ? '●' : '○' }}</td>
          <td>@if (x.active && auth.can('support.grant')) { <button class="link danger" (click)="revoke(x.id)">{{ 'support.revoke' | t }}</button> }</td></tr>
      }</tbody>
    </table>
    <h2>{{ 'support.interventions' | t }}</h2>
    @if (auth.can('managed.request')) {
      <form class="card inline" (ngSubmit)="request()">
        <input name="title" [placeholder]="'support.title' | t" [(ngModel)]="title" required />
        <select name="priority" [(ngModel)]="priority"><option>LOW</option><option>NORMAL</option><option>HIGH</option><option>URGENT</option></select>
        <button type="submit">{{ 'support.request' | t }}</button>
      </form>
    }
    <table><tbody>@for (i of interventions(); track i.id) {
      <tr><td>{{ i.title }}</td><td>{{ i.priority }}</td><td><span class="pill" [class]="i.status">{{ i.status }}</span></td><td>{{ i.report }}</td></tr>
    }</tbody></table>
  `,
})
export class SupportPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);
  contracts = signal<any[]>([]);
  grants = signal<any[]>([]);
  interventions = signal<any[]>([]);
  g = { staffEmail: '', level: 'READ', hours: 4, reason: '' };
  title = '';
  priority = 'NORMAL';
  ngOnInit() { this.load(); }
  async load() {
    this.contracts.set(await this.api.get('/managed/contracts'));
    this.grants.set(await this.api.get('/managed/support-access'));
    this.interventions.set(await this.api.get('/managed/interventions'));
  }
  async grant() { await this.api.post('/managed/support-access', this.g); await this.load(); }
  async revoke(id: string) { await this.api.delete(`/managed/support-access/${id}`); await this.load(); }
  async request() { await this.api.post('/managed/interventions', { title: this.title, priority: this.priority }); this.title = ''; await this.load(); }
}

@Component({
  selector: 'ap-billing',
  imports: [TPipe],
  template: `
    <h1>{{ 'nav.billing' | t }}</h1>
    @if (overview(); as o) {
      <section class="card">
        <h2>{{ 'billing.plan' | t }} : {{ o.plan.name }} <span class="pill" [class]="o.subscription.status">{{ o.subscription.status }}</span></h2>
        <p>{{ 'billing.usage' | t }} : {{ o.usage.servers }}/{{ o.usage.serverQuota }} serveurs · {{ o.usage.members }}/{{ o.usage.memberQuota }} membres</p>
        <p class="muted">→ {{ i18n.date(o.subscription.currentPeriodEnd) }}</p>
      </section>
      <div class="plans">
        @for (p of plans(); track p.code) {
          <div class="card plan">
            <h3>{{ p.name }}</h3>
            <p>{{ price(p, o.subscription.currency) }} / mois</p>
            <p>{{ p.serverQuota }} serveurs · {{ p.memberQuota }} membres</p>
            @if (auth.can('billing.manage') && p.code !== o.plan.code) { <button (click)="change(p.code)">{{ 'billing.change' | t }}</button> }
          </div>
        }
      </div>
      <h2>{{ 'billing.invoices' | t }}</h2>
      <table><tbody>@for (i of o.invoices; track i.id) {
        <tr><td><code>{{ i.number }}</code></td><td>{{ i18n.money(i.amountMinor, i.currency) }}</td>
          <td><span class="pill" [class]="i.status">{{ i.status }}</span></td><td>{{ i18n.date(i.issuedAt) }}</td></tr>
      }</tbody></table>
      @if (instructions()) { <p class="card warn">{{ instructions() }}</p> }
    }
  `,
})
export class BillingPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);
  overview = signal<any | null>(null);
  plans = signal<any[]>([]);
  instructions = signal<string | null>(null);
  ngOnInit() { this.load(); }
  async load() {
    this.overview.set(await this.api.get('/billing'));
    this.plans.set(await this.api.public('/plans'));
  }
  price(p: any, currency: string): string {
    const x = p.prices.find((y: any) => y.currency === currency && y.period === 'MONTH') ?? p.prices[0];
    return x ? this.i18n.money(x.amountMinor, x.currency) : '—';
  }
  async change(plan: string) {
    const r = await this.api.post('/billing/change-plan', { plan, period: 'MONTH' });
    this.instructions.set(r.instructions ?? null);
    await this.load();
  }
}

@Component({
  selector: 'ap-audit',
  imports: [TPipe],
  template: `
    <h1>{{ 'nav.audit' | t }} <button class="secondary" (click)="verify()">{{ 'audit.verify' | t }}</button></h1>
    @if (verification(); as v) {
      <p class="card" [class.warn]="!v.valid">{{ v.valid ? ('audit.valid' | t) : ('audit.broken' | t) + v.brokenAtSeq }} · {{ v.events }}</p>
    }
    <table>
      <thead><tr><th>#</th><th>Date</th><th>Actor</th><th>Action</th><th>Result</th><th>Details</th></tr></thead>
      <tbody>@for (e of events(); track e.id) {
        <tr><td>{{ e.seq }}</td><td>{{ i18n.date(e.occurredAt) }}</td><td>{{ e.actorType }}</td><td>{{ e.action }}</td>
          <td><span class="pill" [class]="e.outcome">{{ e.outcome }}</span></td><td><code>{{ short(e.details) }}</code></td></tr>
      }</tbody>
    </table>
  `,
})
export class AuditPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly i18n = inject(I18n);
  events = signal<any[]>([]);
  verification = signal<any | null>(null);
  ngOnInit() { this.load(); }
  async load() { this.events.set((await this.api.get('/audit-events?size=100')).items); }
  async verify() { this.verification.set(await this.api.get('/audit-events/verify')); }
  short(d: any): string { const s = JSON.stringify(d ?? {}); return s.length > 90 ? s.slice(0, 90) + '…' : s; }
}
