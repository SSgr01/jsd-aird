/** Univer permits one active group. Nested field/format writes join that group. */
export class WorkbookUndoBatch {
  private active?: { depth: number; group: { dispose(): void } };
  begin(open: () => { dispose(): void } | undefined) {
    if (!this.active) {
      const group = open();
      if (!group) return;
      this.active = { depth: 0, group };
    }
    const active = this.active; active.depth++;
    let disposed = false;
    return { dispose: () => {
      if (disposed) return;
      disposed = true;
      if (--active.depth === 0) { this.active = undefined; active.group.dispose(); }
    } };
  }
}
