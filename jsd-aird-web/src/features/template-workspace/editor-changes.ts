/** A per-instance, bounded scheduler. Recording is synchronous; notifying is batched. */
export class EditorChanges {
  private frame = 0;
  private timer = 0;
  private dirty = false;
  private disposed = false;
  private flushing = false;
  private effects: Array<() => void> = [];
  private synchronizations = new Map<string, () => void>();
  constructor(private readonly changed: () => void, private readonly record: () => void) {
    window.addEventListener('blur', this.flush, true);
    document.addEventListener('visibilitychange', this.onVisibility);
  }
  change() { if (!this.disposed) { this.record(); this.dirty = true; this.schedule(); } }
  synchronize(key: string, effect: () => void) { if (!this.disposed) { this.synchronizations.set(key, effect); this.schedule(); } }
  enqueue(effect: () => void) { if (!this.disposed) { this.effects.push(effect); this.schedule(); } }
  private schedule() {
    if (this.frame || this.flushing) return;
    this.frame = window.requestAnimationFrame(this.flush);
    this.timer = window.setTimeout(this.flush, 100);
  }
  private cancel() { window.cancelAnimationFrame(this.frame); window.clearTimeout(this.timer); this.frame = this.timer = 0; }
  private onVisibility = () => { if (document.hidden) this.flush(); };
  flush = () => {
    if (this.disposed || this.flushing) return;
    this.cancel(); this.flushing = true;
    let dirty = false;
    try {
      do {
        dirty ||= this.dirty; this.dirty = false;
        const effects = [...this.effects, ...this.synchronizations.values()];
        this.effects = []; this.synchronizations.clear();
        for (const effect of effects) { if (this.disposed) break; effect(); }
      } while (!this.disposed && (this.dirty || this.effects.length || this.synchronizations.size));
    }
    finally {
      try { if (dirty && !this.disposed) this.changed(); }
      finally {
        this.flushing = false;
        if (!this.disposed && (this.dirty || this.effects.length || this.synchronizations.size)) this.schedule();
      }
    }
  };
  dispose() { this.disposed = true; this.cancel(); this.effects = []; this.synchronizations.clear(); window.removeEventListener('blur', this.flush, true); document.removeEventListener('visibilitychange', this.onVisibility); }
}

export interface EditorCommand { id: string; type?: number; params?: object }
export function isPersistentMutation(command: EditorCommand, options?: { fromChangeset?: boolean; syncOnly?: boolean; applyFormulaCalculationResult?: boolean }) {
  if (options?.fromChangeset || options?.syncOnly || options?.applyFormulaCalculationResult || command.type !== 2) return false;
  if (/permission|authz|calculation|calc-result|set-zoom|setZoom|set-selections/i.test(command.id) || command.id.startsWith('formula.')) return false;
  if (command.id === 'doc.mutation.rich-text-editing') {
    const actions = (command.params as { actions?: unknown[] } | undefined)?.actions;
    if (!actions?.length) return false;
  }
  return true;
}
