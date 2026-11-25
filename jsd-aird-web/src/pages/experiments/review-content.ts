import type { ExperimentModel } from '@/services/experiments/experiment-api';

const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value)
  ? value as Record<string, unknown> : {};
const text = (value: unknown) => typeof value === 'string' ? value : '';

// Initialization, calculation caches and canvas state are not new experimental data.
export function reviewContentKey(model: ExperimentModel): string {
  const snapshot = object(model.documentSnapshot);
  const sheets: Record<string, unknown> = {};
  for (const [id, rawSheet] of Object.entries(object(snapshot.sheets))) {
    const sheet = object(rawSheet);
    if (String(sheet.name ?? '').startsWith('_JSD')) continue;
    const cells: Record<string, unknown> = {};
    for (const [row, columns] of Object.entries(object(sheet.cellData))) {
      for (const [column, raw] of Object.entries(object(columns))) {
        const cell = object(raw);
        const richText = text(object(object(cell.p).body).dataStream);
        if (cell.f) cells[`${row}:${column}`] = { f: cell.f };
        else if (cell.v !== undefined && cell.v !== null && cell.v !== '') cells[`${row}:${column}`] = { v: cell.v };
        else if (richText.trim()) cells[`${row}:${column}`] = { v: richText.trimEnd() };
      }
    }
    const custom = object(sheet.custom);
    const instance = custom.templateInstance ? object(custom.templateInstance) : undefined;
    sheets[id] = { cells, structure: instance ? Object.fromEntries(
      ['model', 'mapping', 'execution', 'records', 'items'].filter(key => key in instance).map(key => [key, instance[key]]),
    ) : custom.labTemplate };
  }
  const stable = (value: unknown): unknown => Array.isArray(value) ? value.map(stable)
    : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, stable(item)])) : value;
  return JSON.stringify(stable(snapshot.sheets ? sheets : { body: snapshot.body, values: model.dynamicValues }));
}
