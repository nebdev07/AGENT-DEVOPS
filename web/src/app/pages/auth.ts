import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Auth } from '../core/api';
import { I18n, TPipe } from '../core/i18n';

@Component({
  selector: 'ap-login',
  imports: [FormsModule, RouterLink, TPipe],
  template: `
    <div class="auth-shell">
      <section class="auth card">
        <a class="auth-brand" routerLink="/">
          <span class="mark" aria-hidden="true"></span>
          {{ 'app.title' | t }}
        </a>
        <h1>{{ 'auth.login' | t }}</h1>
        <p class="muted" style="margin-top:-0.35rem;margin-bottom:1rem">{{ 'auth.loginLede' | t }}</p>
        <form (ngSubmit)="submit()">
          <label>{{ 'auth.email' | t }}<input name="email" type="email" [(ngModel)]="email" required autocomplete="username" /></label>
          <label>{{ 'auth.password' | t }}<input name="password" type="password" [(ngModel)]="password" required autocomplete="current-password" /></label>
          @if (needTotp()) {
            <label>{{ 'auth.totp' | t }}<input name="totp" inputmode="numeric" [(ngModel)]="totp" autocomplete="one-time-code" /></label>
          }
          <button type="submit" [disabled]="busy()">{{ 'auth.login' | t }}</button>
        </form>
        <p>{{ 'auth.noAccount' | t }} <a routerLink="/register">{{ 'auth.register' | t }}</a></p>
      </section>
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
  imports: [FormsModule, RouterLink, TPipe],
  template: `
    <div class="auth-shell">
      <section class="auth card">
        <a class="auth-brand" routerLink="/">
          <span class="mark" aria-hidden="true"></span>
          {{ 'app.title' | t }}
        </a>
        <h1>{{ 'auth.register' | t }}</h1>
        <p class="muted" style="margin-top:-0.35rem;margin-bottom:1rem">{{ 'auth.registerLede' | t }}</p>
        <form (ngSubmit)="submit()">
          <label>{{ 'auth.name' | t }}<input name="displayName" [(ngModel)]="form.displayName" /></label>
          <label>{{ 'auth.email' | t }}<input name="email" type="email" [(ngModel)]="form.email" required /></label>
          <label>{{ 'auth.password' | t }}<input name="password" type="password" minlength="12" [(ngModel)]="form.password" required autocomplete="new-password" /></label>
          <label>{{ 'auth.org' | t }}<input name="org" [(ngModel)]="form.organizationName" required /></label>
          <label>{{ 'auth.currency' | t }}
            <select name="currency" [(ngModel)]="form.currency"><option>USD</option><option>EUR</option><option>XOF</option></select>
          </label>
          <button type="submit" [disabled]="busy()">{{ 'auth.register' | t }}</button>
        </form>
        <p>{{ 'auth.haveAccount' | t }} <a routerLink="/login">{{ 'auth.login' | t }}</a></p>
      </section>
    </div>
  `,
})
export class RegisterPage {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  busy = signal(false);
  form = { displayName: '', email: '', password: '', organizationName: '', currency: 'USD' };

  async submit() {
    this.busy.set(true);
    try {
      await this.auth.register({ ...this.form, locale: this.i18n.lang(), timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone });
      this.router.navigateByUrl('/servers');
    } catch { /* message affiché par l'intercepteur */ } finally {
      this.busy.set(false);
    }
  }
}
