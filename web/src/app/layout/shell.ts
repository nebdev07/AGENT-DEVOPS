import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Auth } from '../core/api';
import { I18n, Lang, TPipe } from '../core/i18n';

@Component({
  selector: 'ap-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  template: `
    <header class="top">
      <strong class="brand">{{ 'app.title' | t }}</strong>
      @if (auth.orgs().length) {
        <select aria-label="Organization" [value]="auth.orgId()" (change)="switch($any($event.target).value)">
          @for (o of auth.orgs(); track o.id) { <option [value]="o.id">{{ o.name }}</option> }
        </select>
      }
      <span class="spacer"></span>
      <select aria-label="Language" [value]="i18n.lang()" (change)="i18n.set($any($event.target).value)">
        <option value="en">EN</option><option value="fr">FR</option>
      </select>
      <span class="muted">{{ auth.me()?.email }}</span>
      <button class="link" (click)="logout()">{{ 'nav.logout' | t }}</button>
    </header>
    @if (auth.me()?.staffRolePendingMfa) {
      <div class="card warn" style="margin:0;border-radius:0">{{ 'sec.staffPending' | t }} <a routerLink="/security">{{ 'nav.security' | t }}</a></div>
    }
    <div class="layout">
      <nav class="side">
        <a routerLink="/servers" routerLinkActive="active">{{ 'nav.servers' | t }}</a>
        <a routerLink="/operations" routerLinkActive="active">{{ 'nav.operations' | t }}</a>
        @if (auth.can('member.read')) { <a routerLink="/members" routerLinkActive="active">{{ 'nav.members' | t }}</a> }
        @if (auth.can('apitoken.manage')) { <a routerLink="/tokens" routerLinkActive="active">{{ 'nav.tokens' | t }}</a> }
        @if (auth.can('managed.read')) { <a routerLink="/support" routerLinkActive="active">{{ 'nav.support' | t }}</a> }
        @if (auth.can('billing.read')) { <a routerLink="/billing" routerLinkActive="active">{{ 'nav.billing' | t }}</a> }
        @if (auth.can('audit.read')) { <a routerLink="/audit" routerLinkActive="active">{{ 'nav.audit' | t }}</a> }
        <a routerLink="/security" routerLinkActive="active">{{ 'nav.security' | t }}</a>
        @if (auth.me()?.staffRole === 'PLATFORM_ADMIN') { <a routerLink="/platform" routerLinkActive="active">{{ 'nav.platform' | t }}</a> }
      </nav>
      <main class="content"><router-outlet /></main>
    </div>
  `,
})
export class Shell {
  protected readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);
  private readonly router = inject(Router);

  switch(id: string) {
    this.auth.selectOrg(id);
    this.router.navigateByUrl('/servers');
  }

  async logout() {
    await this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}

export type { Lang };
