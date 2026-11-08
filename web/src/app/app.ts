import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Notice } from './core/api';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `
    @if (notice.error(); as e) {
      <div class="toast error" role="alert">{{ e.message }} @if (e.correlationId) {<small>({{ e.correlationId }})</small>}</div>
    }
    @if (notice.info(); as i) { <div class="toast ok" role="status">{{ i }}</div> }
    <router-outlet />
  `,
})
export class App {
  protected readonly notice = inject(Notice);
}
