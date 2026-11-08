import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, Auth } from '../core/api';
import { I18n, TPipe } from '../core/i18n';

@Component({
  selector: 'ap-servers',
  imports: [FormsModule, RouterLink, TPipe],
  template: `
    <h1>{{ 'nav.servers' | t }}</h1>
    @if (auth.can('server.manage')) {
      <form class="card inline" (ngSubmit)="add()">
        <input name="name" [placeholder]="'servers.name' | t" [(ngModel)]="form.name" required />
        <input name="host" [placeholder]="'servers.host' | t" [(ngModel)]="form.host" required />
        <input name="port" type="number" min="1" max="65535" [(ngModel)]="form.port" />
        <input name="user" [placeholder]="'servers.user' | t" [(ngModel)]="form.user" required />
        <button type="submit">{{ 'servers.add' | t }}</button>
      </form>
    }
    <table>
      <thead><tr><th>{{ 'servers.name' | t }}</th><th>{{ 'servers.host' | t }}</th><th>{{ 'servers.state' | t }}</th><th>OS</th><th>{{ 'server.trust' | t }}</th></tr></thead>
      <tbody>
        @for (s of servers(); track s.id) {
          <tr>
            <td><a [routerLink]="['/servers', s.id]">{{ s.name }}</a></td>
            <td>{{ s.sshUser }}&#64;{{ s.host }}:{{ s.sshPort }}</td>
            <td><span class="pill" [class]="s.connectionState">{{ s.connectionState }}</span></td>
            <td>{{ s.osFamily ?? '—' }} {{ s.osVersion ?? '' }}</td>
            <td>{{ s.hostFingerprint ? '✓' : '—' }}</td>
          </tr>
        } @empty { <tr><td colspan="5" class="muted">{{ 'common.none' | t }}</td></tr> }
      </tbody>
    </table>
  `,
})
export class ServersPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);
  servers = signal<any[]>([]);
  form = { name: '', host: '', port: 22, user: 'ubuntu' };

  ngOnInit() { this.load(); }

  async load() { this.servers.set(await this.api.get('/servers')); }

  async add() {
    await this.api.post('/servers', this.form);
    this.form = { name: '', host: '', port: 22, user: 'ubuntu' };
    await this.load();
  }
}
