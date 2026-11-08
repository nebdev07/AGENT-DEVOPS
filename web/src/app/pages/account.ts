import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, Auth, Notice } from '../core/api';
import { I18n, TPipe } from '../core/i18n';

/** Sécurité du compte : MFA (obligatoire pour le personnel INOVNE) et mot de passe. */
@Component({
  selector: 'ap-security',
  imports: [FormsModule, TPipe],
  template: `
    <h1>{{ 'nav.security' | t }}</h1>
    @if (auth.me()?.staffRolePendingMfa) {
      <section class="card warn">{{ 'sec.staffPending' | t }} <strong>{{ auth.me()?.staffRolePendingMfa }}</strong></section>
    }
    <section class="card">
      <h2>{{ 'sec.mfa' | t }}</h2>
      @if (auth.me()?.mfaEnabled) {
        <p><span class="pill ACTIVE">{{ 'sec.mfaOn' | t }}</span></p>
        <form class="inline" (ngSubmit)="disable()">
          <input type="password" name="pw" [(ngModel)]="disablePassword" [placeholder]="'auth.password' | t" autocomplete="current-password" required />
          <button class="danger" type="submit">{{ 'sec.disable' | t }}</button>
        </form>
      } @else {
        <p class="muted">{{ 'sec.mfaOff' | t }}</p>
        @if (setup(); as s) {
          <p>{{ 'sec.scan' | t }}</p>
          <p><code>{{ s.secret }}</code></p>
          <details><summary>otpauth://</summary><pre>{{ s.otpauthUri }}</pre></details>
          <form class="inline" (ngSubmit)="enable()">
            <input name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" [(ngModel)]="code" [placeholder]="'auth.totp' | t" required />
            <button type="submit">{{ 'sec.enable' | t }}</button>
          </form>
        } @else {
          <button (click)="start()">{{ 'sec.start' | t }}</button>
        }
      }
    </section>
    <section class="card">
      <h2>{{ 'sec.password' | t }}</h2>
      <form (ngSubmit)="changePassword()">
        <label>{{ 'sec.current' | t }} <input type="password" name="cur" [(ngModel)]="current" autocomplete="current-password" required /></label>
        <label>{{ 'sec.new' | t }} <input type="password" name="new" [(ngModel)]="next" autocomplete="new-password" minlength="12" required /></label>
        <button type="submit">{{ 'common.save' | t }}</button>
      </form>
    </section>
  `,
})
export class SecurityPage {
  private readonly api = inject(Api);
  private readonly notice = inject(Notice);
  private readonly i18n = inject(I18n);
  protected readonly auth = inject(Auth);
  setup = signal<{ secret: string; otpauthUri: string } | null>(null);
  code = '';
  disablePassword = '';
  current = '';
  next = '';

  async start() { this.setup.set(await this.api.rootPost('/me/mfa/setup')); }

  async enable() {
    await this.api.rootPost('/me/mfa/enable', { code: this.code.trim() });
    this.setup.set(null);
    this.code = '';
    await this.auth.refresh();
    this.notice.ok(this.i18n.t('sec.mfaOn'));
  }

  async disable() {
    await this.api.rootPost('/me/mfa/disable', { password: this.disablePassword });
    this.disablePassword = '';
    await this.auth.refresh();
  }

  async changePassword() {
    await this.api.rootPost('/me/password', { currentPassword: this.current, newPassword: this.next });
    this.current = this.next = '';
    this.notice.ok(this.i18n.t('sec.changed'));
  }
}

/** Back-office INOVNE (PLATFORM_ADMIN) : métadonnées des organisations et personnel. Aucune donnée client. */
@Component({
  selector: 'ap-platform',
  imports: [FormsModule, TPipe],
  template: `
    <h1>{{ 'nav.platform' | t }} <button class="secondary" (click)="load()">{{ 'common.refresh' | t }}</button></h1>
    <section class="card">
      <h2>{{ 'plat.orgs' | t }}</h2>
      <table>
        <thead><tr><th>{{ 'servers.name' | t }}</th><th>{{ 'billing.plan' | t }}</th><th>{{ 'common.status' | t }}</th><th>{{ 'nav.servers' | t }}</th><th>{{ 'nav.members' | t }}</th><th>{{ 'common.created' | t }}</th><th></th></tr></thead>
        <tbody>
          @for (o of orgs(); track o.id) {
            <tr>
              <td>{{ o.name }}</td>
              <td>{{ o.plan }} <span class="pill" [class]="o.subscriptionStatus">{{ o.subscriptionStatus }}</span></td>
              <td><span class="pill" [class]="o.status">{{ o.status }}</span></td>
              <td>{{ o.servers }}</td><td>{{ o.members }}</td><td>{{ i18n.date(o.createdAt) }}</td>
              <td>
                @if (o.status === 'ACTIVE') { <button class="link danger" (click)="status(o.id, 'SUSPENDED')">{{ 'plat.suspend' | t }}</button> }
                @else { <button class="link" (click)="status(o.id, 'ACTIVE')">{{ 'plat.reactivate' | t }}</button> }
              </td>
            </tr>
          } @empty { <tr><td colspan="7" class="muted">{{ 'common.none' | t }}</td></tr> }
        </tbody>
      </table>
    </section>
    <section class="card">
      <h2>{{ 'plat.staff' | t }}</h2>
      <table>
        <thead><tr><th>{{ 'auth.email' | t }}</th><th>{{ 'members.role' | t }}</th><th>MFA</th><th></th></tr></thead>
        <tbody>
          @for (s of staff(); track s.userId) {
            <tr><td>{{ s.email }}</td><td>{{ s.role }}</td>
              <td>@if (s.mfaEnabled) { <span class="pill ACTIVE">ON</span> } @else { <span class="pill FAILED">{{ 'plat.inert' | t }}</span> }</td>
              <td><button class="link danger" (click)="remove(s.userId)">{{ 'common.delete' | t }}</button></td></tr>
          }
        </tbody>
      </table>
      <form class="inline" (ngSubmit)="add()">
        <input name="email" type="email" [(ngModel)]="email" [placeholder]="'auth.email' | t" required />
        <select name="role" [(ngModel)]="role"><option>PLATFORM_OPERATOR</option><option>PLATFORM_ADMIN</option></select>
        <button type="submit">{{ 'common.add' | t }}</button>
      </form>
    </section>
  `,
})
export class PlatformPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly i18n = inject(I18n);
  orgs = signal<any[]>([]);
  staff = signal<any[]>([]);
  email = '';
  role = 'PLATFORM_OPERATOR';

  ngOnInit() { this.load(); }

  async load() {
    this.orgs.set(await this.api.rootGet('/admin/organizations'));
    this.staff.set(await this.api.rootGet('/admin/staff'));
  }

  async status(id: string, status: string) { await this.api.rootPost(`/admin/organizations/${id}/status`, { status }); await this.load(); }

  async add() { await this.api.rootPost('/admin/staff', { email: this.email.trim(), role: this.role }); this.email = ''; await this.load(); }

  async remove(userId: string) { await this.api.rootDelete('/admin/staff/' + userId); await this.load(); }
}
