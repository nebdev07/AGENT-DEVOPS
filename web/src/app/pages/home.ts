import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Auth } from '../core/api';
import { I18n, TPipe } from '../core/i18n';

/** Accueil public — même composition / tokens que la console (langage platform). */
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
          <a href="#solutions">{{ 'home.navSolutions' | t }}</a>
          <a href="#method">{{ 'home.navMethod' | t }}</a>
          <a href="#capabilities">{{ 'home.navCapabilities' | t }}</a>
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

      <section class="hero landing-hero rise" aria-labelledby="brandHero">
        <div class="hero-copy">
          <h1 id="brandHero" class="brand-signal">{{ 'app.title' | t }}</h1>
          <p class="hero-tagline">{{ 'home.tagline' | t }}</p>
          <p class="lede">{{ 'home.lede' | t }}</p>
          <div class="hero-actions">
            @if (auth.token()) {
              <a class="btn" routerLink="/servers">{{ 'home.openConsole' | t }}</a>
              <a class="btn ghost" href="#solutions">{{ 'home.seeSolutions' | t }}</a>
            } @else {
              <a class="btn" routerLink="/login">{{ 'auth.login' | t }}</a>
              <a class="btn ghost" href="#solutions">{{ 'home.seeSolutions' | t }}</a>
            }
          </div>
        </div>
        <ol class="hero-steps" [attr.aria-label]="'home.navMethod' | t">
          <li>
            <span>{{ 'home.heroStep1K' | t }}</span>
            <strong>{{ 'home.heroStep1T' | t }}</strong>
            <em>{{ 'home.heroStep1E' | t }}</em>
          </li>
          <li>
            <span>{{ 'home.heroStep2K' | t }}</span>
            <strong>{{ 'home.heroStep2T' | t }}</strong>
            <em>{{ 'home.heroStep2E' | t }}</em>
          </li>
          <li>
            <span>{{ 'home.heroStep3K' | t }}</span>
            <strong>{{ 'home.heroStep3T' | t }}</strong>
            <em>{{ 'home.heroStep3E' | t }}</em>
          </li>
        </ol>
      </section>

      <section id="solutions" class="band" aria-labelledby="solutionsTitle">
        <p class="kicker rise d2">{{ 'home.navSolutions' | t }}</p>
        <h2 id="solutionsTitle" class="solutions-title rise d3">{{ 'home.solutionsTitle' | t }}</h2>
        <p class="band-lede rise d4">{{ 'home.solutionsLede' | t }}</p>
        <div class="solutions-grid">
          <article class="solution rise d3">
            <span class="solution-num">01</span>
            <h3>{{ 'home.sol1Title' | t }}</h3>
            <p>{{ 'home.sol1Body' | t }}</p>
          </article>
          <article class="solution rise d4">
            <span class="solution-num">02</span>
            <h3>{{ 'home.sol2Title' | t }}</h3>
            <p>{{ 'home.sol2Body' | t }}</p>
          </article>
          <article class="solution rise d5">
            <span class="solution-num">03</span>
            <h3>{{ 'home.sol3Title' | t }}</h3>
            <p>{{ 'home.sol3Body' | t }}</p>
          </article>
        </div>
      </section>

      <section id="method" class="band" aria-labelledby="methodTitle">
        <p class="kicker">{{ 'home.navMethod' | t }}</p>
        <h2 id="methodTitle" class="solutions-title">{{ 'home.riskTitle' | t }}</h2>
        <p class="band-lede">{{ 'home.riskLede' | t }}</p>
        <div class="pillars">
          <article class="pillar">
            <i class="dot read" aria-hidden="true"></i>
            <h3>{{ 'home.risk1Title' | t }}</h3>
            <p>{{ 'home.risk1Body' | t }}</p>
          </article>
          <article class="pillar">
            <i class="dot change" aria-hidden="true"></i>
            <h3>{{ 'home.risk2Title' | t }}</h3>
            <p>{{ 'home.risk2Body' | t }}</p>
          </article>
          <article class="pillar">
            <i class="dot dest" aria-hidden="true"></i>
            <h3>{{ 'home.risk3Title' | t }}</h3>
            <p>{{ 'home.risk3Body' | t }}</p>
          </article>
        </div>
      </section>

      <section id="capabilities" class="band" aria-labelledby="capTitle">
        <p class="kicker">{{ 'home.navCapabilities' | t }}</p>
        <h2 id="capTitle" class="solutions-title">{{ 'home.capTitle' | t }}</h2>
        <p class="band-lede">{{ 'home.capLede' | t }}</p>
        <div class="capabilities">
          <article class="capability">
            <span class="num">01</span>
            <h3>{{ 'home.cap1Title' | t }}</h3>
            <p>{{ 'home.cap1Body' | t }}</p>
          </article>
          <article class="capability">
            <span class="num">02</span>
            <h3>{{ 'home.cap2Title' | t }}</h3>
            <p>{{ 'home.cap2Body' | t }}</p>
          </article>
          <article class="capability">
            <span class="num">03</span>
            <h3>{{ 'home.cap3Title' | t }}</h3>
            <p>{{ 'home.cap3Body' | t }}</p>
          </article>
        </div>
      </section>

      <section class="landing-cta" aria-labelledby="ctaTitle">
        <h2 id="ctaTitle">{{ 'home.ctaTitle' | t }}</h2>
        <p>{{ 'home.ctaLede' | t }}</p>
        <div class="hero-actions">
          @if (auth.token()) {
            <a class="btn" routerLink="/servers">{{ 'home.openConsole' | t }}</a>
          } @else {
            <a class="btn" routerLink="/login">{{ 'auth.login' | t }}</a>
            <a class="btn ghost" routerLink="/register">{{ 'auth.register' | t }}</a>
          }
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
