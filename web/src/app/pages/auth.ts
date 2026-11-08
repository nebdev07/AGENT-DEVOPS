import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Auth, API, Notice } from '../core/api';
import { AuthChrome } from '../layout/auth-chrome';
import { I18n, TPipe } from '../core/i18n';

@Component({
  selector: 'ap-login',
  imports: [FormsModule, RouterLink, TPipe, AuthChrome],
  template: `
    <div class="public-shell auth-page">
      <ap-auth-chrome />
      <div class="auth-body">
        <aside class="auth-aside card">
          <p class="kicker">{{ 'app.title' | t }}</p>
          <h2>{{ 'home.tagline' | t }}</h2>
          <p class="muted">{{ 'auth.asideLogin' | t }}</p>
          <ul class="auth-bullets">
            <li>{{ 'home.heroStep2T' | t }}</li>
            <li>{{ 'home.heroStep3T' | t }}</li>
          </ul>
        </aside>
        <section class="auth card">
          <h1>{{ 'auth.login' | t }}</h1>
          <p class="muted auth-lede">{{ 'auth.loginLede' | t }}</p>
          <form (ngSubmit)="submit()">
            <label>{{ 'auth.email' | t }}<input name="email" type="email" [(ngModel)]="email" required autocomplete="username" /></label>
            <label>{{ 'auth.password' | t }}<input name="password" type="password" [(ngModel)]="password" required autocomplete="current-password" /></label>
            @if (needTotp()) {
              <label>{{ 'auth.totp' | t }}<input name="totp" inputmode="numeric" [(ngModel)]="totp" autocomplete="one-time-code" /></label>
            }
            <button type="submit" [disabled]="busy()">{{ 'auth.login' | t }}</button>
          </form>
          <p class="auth-foot">{{ 'auth.noAccount' | t }} <a routerLink="/register">{{ 'auth.register' | t }}</a></p>
        </section>
      </div>
    </div>
  `,
})
export class LoginPage {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);
  email = '';
  password = '';
  totp = '';
  needTotp = signal(false);
  busy = signal(false);

  async submit() {
    this.busy.set(true);
    try {
      await this.auth.login(this.email, this.password, this.totp);
      this.router.navigateByUrl('/servers');
    } catch (e: any) {
      if (e?.code === 'mfa_required') this.needTotp.set(true);
    } finally {
      this.busy.set(false);
    }
  }
}

@Component({
  selector: 'ap-register',
  imports: [FormsModule, RouterLink, TPipe, AuthChrome],
  template: `
    <div class="public-shell auth-page">
      <ap-auth-chrome />
      <div class="auth-body">
        <aside class="auth-aside card">
          <p class="kicker">{{ 'auth.register' | t }}</p>
          <h2>{{ 'auth.asideRegisterTitle' | t }}</h2>
          <p class="muted">{{ 'auth.asideRegister' | t }}</p>
          <ol class="auth-bullets">
            <li>{{ 'auth.fieldName' | t }}</li>
            <li>{{ 'auth.fieldEmail' | t }}</li>
            <li>{{ 'auth.fieldOrg' | t }}</li>
          </ol>
          <p class="muted">{{ 'auth.noPasswordYet' | t }}</p>
        </aside>
        @if (sent()) {
          <section class="auth card">
            <h1>{{ 'auth.requestSentTitle' | t }}</h1>
            <p class="muted">{{ 'auth.requestSentBody' | t }}</p>
            <div class="hero-actions">
              <a class="btn" routerLink="/">{{ 'auth.backHome' | t }}</a>
              <a class="btn ghost" routerLink="/login">{{ 'auth.login' | t }}</a>
            </div>
          </section>
        } @else {
          <section class="auth card">
            <h1>{{ approvalRequired() ? ('auth.requestAccess' | t) : ('auth.register' | t) }}</h1>
            <p class="muted auth-lede">{{ approvalRequired() ? ('auth.registerLedeApproval' | t) : ('auth.registerLede' | t) }}</p>
            <form (ngSubmit)="submit()">
              <label>{{ 'auth.name' | t }}<input name="displayName" [(ngModel)]="form.displayName" autocomplete="name" /></label>
              <label>{{ 'auth.email' | t }}<input name="email" type="email" [(ngModel)]="form.email" required autocomplete="email" /></label>
              <label>{{ 'auth.org' | t }}<input name="org" [(ngModel)]="form.organizationName" required /></label>
              <label>{{ 'auth.message' | t }}<textarea name="message" rows="3" [(ngModel)]="form.message" [placeholder]="'auth.messagePh' | t"></textarea></label>
              @if (!approvalRequired()) {
                <label>{{ 'auth.password' | t }}<input name="password" type="password" minlength="12" [(ngModel)]="form.password" required autocomplete="new-password" /></label>
                <label>{{ 'auth.currency' | t }}
                  <select name="currency" [(ngModel)]="form.currency"><option>USD</option><option>EUR</option><option>XOF</option></select>
                </label>
              }
              <button type="submit" [disabled]="busy()">{{ approvalRequired() ? ('auth.submitRequest' | t) : ('auth.register' | t) }}</button>
            </form>
            <p class="auth-foot">{{ 'auth.haveAccount' | t }} <a routerLink="/login">{{ 'auth.login' | t }}</a></p>
          </section>
        }
      </div>
    </div>
  `,
})
export class RegisterPage implements OnInit {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);
  private readonly notice = inject(Notice);
  protected readonly i18n = inject(I18n);
  busy = signal(false);
  sent = signal(false);
  approvalRequired = signal(true);
  form = { displayName: '', email: '', organizationName: '', password: '', currency: 'USD', message: '' };

  async ngOnInit() {
    try {
      const cfg: any = await firstValueFrom(this.http.get(`${API}/public/site-config`));
      this.approvalRequired.set(!!cfg.signupRequiresApproval);
    } catch {
      this.approvalRequired.set(true);
    }
  }

  async submit() {
    this.busy.set(true);
    try {
      if (this.approvalRequired()) {
        await firstValueFrom(this.http.post(`${API}/public/auth/signup-request`, {
          displayName: this.form.displayName,
          email: this.form.email,
          organizationName: this.form.organizationName,
          message: this.form.message || undefined,
          locale: this.i18n.lang(),
          timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        }));
        this.sent.set(true);
        this.notice.ok(this.i18n.t('auth.requestSentTitle'));
      } else {
        await this.auth.register({
          ...this.form,
          locale: this.i18n.lang(),
          timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        });
        this.router.navigateByUrl('/servers');
      }
    } catch { /* intercepteur */ } finally {
      this.busy.set(false);
    }
  }
}
