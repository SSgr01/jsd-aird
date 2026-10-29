/** Persistent mutations; operations such as caret movement and recalculation are transient. */
export function isWorkbookContentCommand(command: { id: string; type?: number }) {
  const id = command.id;
  if (/^(formula\.|doc\.)/.test(id) || /permission|calculation|calc-result/.test(id)) return false;
  return command.type === 2 || id.includes('.mutation.');
}

export function documentContentSignature(snapshot: object) {
  const data = snapshot as Record<string, unknown>;
  return JSON.stringify(Object.fromEntries([
    'body', 'lists', 'tableSource', 'drawings', 'resources',
    'documentStyle', 'headers', 'footers', 'drawingsOrder', 'notes', 'noteSettings', 'settings',
  ].map(key => [key, data[key]])));
}
