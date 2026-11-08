import { JsonPipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, input, signal } from '@angular/core';
import { Api, Auth } from '../core/api';
import { I18n, TPipe } from '../core/i18n';

@Component({
  selector: 'ap-operations',
  imports: [TPipe, JsonPipe],
  template: `
    <h1>{{ 'nav.operations' | t }} <button class="secondary" (click)="load()">{{ 'common.refresh' | t }}</button></h1>
    <div class="split">
      <table>
        <thead><tr><th>{{ 'ops.type' | t }}</th><th>{{ 'ops.risk' | t }}</th><th>{{ 'common.status' | t }}</th><th>{{ 'common.created' | t }}</th></tr></thead>
        <tbody>
          @for (o of operations(); track o.id) {
            <tr (click)="open(o.id)" [class.selected]="selected()?.id === o.id">
              <td>{{ o.label }}</td>
              <td><span class="pill" [class]="o.risk">{{ o.risk }}</span></td>
              <td><span class="pill" [class]="o.status">{{ o.status }}</span></td>
              <td>{{ i18n.date(o.createdAt) }}</td>
            </tr>
          } @empty { <tr><td colspan="4" class="muted">{{ 'common.none' | t }}</td></tr> }
        </tbody>
      </table>
      @if (selected(); as op) {
        <section class="card detail">
          <h2>{{ op.label }} <span class="pill" [class]="op.status">{{ op.status }}</span></h2>
          <p class="muted">{{ op.origin }} · {{ op.reason }}</p>
          <h3>{{ 'ops.plan' | t }}</h3>
          <ol>@for (step of op.plan?.steps ?? []; track $index) { <li>{{ step }}</li> }</ol>
          <p><strong>Rollback :</strong> {{ op.plan?.rollback }}</p>
          <p>{{ 'ops.hash' | t }} : <code>{{ op.planHash }}</code></p>
          <details><summary>JSON</summary><pre>{{ op.plan | json }}</pre></details>
          <div class="actions">
            @if (op.status === 'PROPOSED' && auth.can('operation.approve')) {
              <button (click)="act(op, 'approve')">{{ 'ops.approve' | t }}</button>
              <button class="danger" (click)="act(op, 'reject')">{{ 'ops.reject' | t }}</button>
            }
            @if (op.status === 'DRAFT' && auth.can('operation.propose')) { <button (click)="act(op, 'submit')">{{ 'ops.submit' | t }}</button> }
            @if (['DRAFT','PROPOSED','APPROVED'].includes(op.status)) { <button class="secondary" (click)="act(op, 'cancel')">{{ 'ops.cancel' | t }}</button> }
          </div>
          <h3>{{ 'ops.executions' | t }}</h3>
          @for (e of op.executions; track e.id) {
            <div class="exec"><span class="pill" [class]="e.status">{{ e.status }}</span> {{ i18n.date(e.startedAt) }} → {{ i18n.date(e.endedAt) }}
              @if (e.error) { <p class="err">{{ e.error }}</p> }
              @if (e.output) { <details><summary>{{ 'ops.output' | t }} (sha256 {{ e.outputSha256?.slice(0, 12) }}…)</summary><pre>{{ e.output }}</pre></details> }
            </div>
          } @empty { <p class="muted">{{ 'common.none' | t }}</p> }
        </section>
      }
    </div>
  `,
})
export class OperationsPage implements OnInit, OnDestroy {
  readonly focus = input<string>();
  private readonly api = inject(Api);
  protected readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);
  operations = signal<any[]>([]);
  selected = signal<any | null>(null);
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit() {
    this.load();
    if (this.focus()) this.open(this.focus()!);
    // Suivi des exécutions en cours, sans WebSocket : rafraîchissement léger.
    this.timer = setInterval(() => {
      const s = this.selected();
      if (s && ['QUEUED', 'RUNNING', 'APPROVED'].includes(s.status)) { this.open(s.id); this.load(); }
    }, 4000);
  }

  ngOnDestroy() { clearInterval(this.timer); }

  async load() { this.operations.set(await this.api.get('/operations?size=100')); }

  async open(id: string) { this.selected.set(await this.api.get('/operations/' + id)); }

  async act(op: any, action: 'approve' | 'reject' | 'cancel' | 'submit') {
    const body = action === 'approve' ? { planHash: op.planHash } : {};
    const r = await this.api.post(`/operations/${op.id}/${action}`, body);
    await this.load();
    await this.open(r.id);
  }
}
