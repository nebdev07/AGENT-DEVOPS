import { Location } from '@angular/common';
import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { I18n, TPipe } from '../core/i18n';

/** En-tête partagé accueil / login / inscription. */
@Component({
  selector: 'ap-auth-chrome',
  imports: [RouterLink, TPipe],
  template: `
    <header class="site-top">
      <a class="site-brand" routerLink="/">
        <span class="mark" aria-hidden="true"></span>
        <strong>{{ 'app.title' | t }}</strong>
      </a>
      <nav class="site-nav" aria-label="Principal">
        <a routerLink="/">{{ 'auth.backHome' | t }}</a>
        <a routerLink="/" fragment="solutions">{{ 'home.navSolutions' | t }}</a>
        <button type="button" class="btn ghost nav-back" (click)="back()">{{ 'auth.back' | t }}</button>
      </nav>
      <select class="lang" aria-label="Language" [value]="i18n.lang()" (change)="i18n.set($any($event.target).value)">
        <option value="fr">FR</option><option value="en">EN</option>
      </select>
    </header>
  `,
})
export class AuthChrome {
  protected readonly i18n = inject(I18n);
  private readonly location = inject(Location);

  back() {
    if (window.history.length > 1) {
      this.location.back();
    } else {
      window.location.href = '/app/';
    }
  }
}
