import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Auth } from '../core/api';
import { I18n, TPipe } from '../core/i18n';

/** Accueil public : marque + méthode, sans forcer la connexion. */
@Component({
  selector: 'ap-home',
  imports: [RouterLink, TPipe],
  template: `
    <div class="public-shell">
      <header class="site-top">
        <a class="site-brand" routerLink="/">
          <span class="mark" aria-hidden="true"></span>
          <strong>{{ 'app.title' | t }}</strong>
        </a>
        <nav class="site-nav" aria-label="Principal">
          <a href="#method">{{ 'home.navMethod' | t }}</a>
          <a href="#solutions">{{ 'home.navSolutions' | t }}</a>
          @if (auth.token()) {
            <a class="nav-cta" routerLink="/servers">{{ 'home.openConsole' | t }}</a>
          } @else {
            <a routerLink="/login">{{ 'auth.login' | t }}</a>
            <a class="nav-cta" routerLink="/register">{{ 'auth.register' | t }}</a>
          }
        </nav>
        <select class="lang" aria-label="Language" [value]="i18n.lang()" (change)="i18n.set($any($event.target).value)">
          <option value="fr">FR</option><option value="en">EN</option>
        </select>
      </header>

      <section class="hero" aria-labelledby="brandHero">
        <div class="hero-plane" aria-hidden="true"></div>
        <div class="hero-copy rise">
          <h1 id="brandHero" class="brand-signal">{{ 'app.title' | t }}</h1>
          <p class="hero-tagline">{{ 'home.tagline' | t }}</p>
          <p class="lede">{{ 'home.lede' | t }}</p>
          <div class="hero-actions">
            @if (auth.token()) {
              <a class="btn" routerLink="/servers">{{ 'home.openConsole' | t }}</a>
            } @else {
              <a class="btn" routerLink="/login">{{ 'auth.login' | t }}</a>
              <a class="btn ghost" routerLink="/register">{{ 'auth.register' | t }}</a>
            }
          </div>
        </div>
        <div class="hero-visual rise d2" aria-hidden="true">
          <div class="visual-panel">
            <div class="visual-bar"><span></span><span></span><span></span></div>
            <div class="visual-row"><em></em><i></i></div>
            <div class="visual-row"><em></em><i class="ok"></i></div>
            <div class="visual-row"><em></em><i class="warn"></i></div>
            <div class="visual-grade">A</div>
          </div>
        </div>
      </section>

      <section id="method" class="band method" aria-labelledby="methodTitle">
        <h2 id="methodTitle">{{ 'home.methodTitle' | t }}</h2>
        <p class="band-lede">{{ 'home.methodLede' | t }}</p>
        <ol class="method-steps">
          <li>
            <span>01</span>
            <strong>{{ 'home.step1Title' | t }}</strong>
            <em>{{ 'home.step1Body' | t }}</em>
          </li>
          <li>
            <span>02</span>
            <strong>{{ 'home.step2Title' | t }}</strong>
            <em>{{ 'home.step2Body' | t }}</em>
          </li>
          <li>
            <span>03</span>
            <strong>{{ 'home.step3Title' | t }}</strong>
            <em>{{ 'home.step3Body' | t }}</em>
          </li>
        </ol>
      </section>

      <section id="solutions" class="band solutions" aria-labelledby="solutionsTitle">
        <h2 id="solutionsTitle">{{ 'home.solutionsTitle' | t }}</h2>
        <p class="band-lede">{{ 'home.solutionsLede' | t }}</p>
        <div class="solutions-grid">
          <article>
            <span class="num">01</span>
            <h3>{{ 'home.sol1Title' | t }}</h3>
            <p>{{ 'home.sol1Body' | t }}</p>
          </article>
          <article>
            <span class="num">02</span>
            <h3>{{ 'home.sol2Title' | t }}</h3>
            <p>{{ 'home.sol2Body' | t }}</p>
          </article>
          <article>
            <span class="num">03</span>
            <h3>{{ 'home.sol3Title' | t }}</h3>
            <p>{{ 'home.sol3Body' | t }}</p>
          </article>
        </div>
      </section>

      <footer class="site-foot">
        <strong>{{ 'app.title' | t }}</strong>
        <span class="muted">{{ 'home.foot' | t }}</span>
      </footer>
    </div>
  `,
})
export class HomePage {
  protected readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);
}
