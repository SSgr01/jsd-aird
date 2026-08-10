/** Pure guards used by the live, non-AI field discovery path. */
export function isSingleCellAddress(address: string) {
  const normalized = address.trim().toUpperCase().replace(/\$/g, '');
  if (!normalized) return false;
  const cells = normalized.split(':');
  return cells.length === 1 || cells[0] === cells[1];
}

/**
 * A short text entered in an unbound ordinary cell is treated as a field
 * label. Values, formulas, URLs and inline `label: value` text are not labels.
 */
export function potentialFieldLabel(value: unknown) {
  if (typeof value !== 'string') return undefined;
  const label = value.trim();
  if (!label || label.length > 40 || /[\r\n]/.test(label)) return undefined;
  if (/^=/.test(label) || /^https?:\/\//i.test(label)) return undefined;
  if (/^[-+]?\d+(?:\.\d+)?%?$/.test(label)) return undefined;
  if (/^.{1,16}[：:]\s*\S{3,}$/.test(label)) return undefined;
  return label.replace(/[：:]$/, '').trim() || undefined;
}

export function isCellMutationCommand(commandId: string) {
  const id = commandId.toLowerCase();
  return [
    'value',
    'set-range-values',
    'set-cell-value',
    'set-values',
    'formula',
    'paste',
    'cut',
    'clear',
    'delete',
    'fill',
    'autofill',
    'cell-data',
    // Univer versions use different command namespaces for direct editing,
    // merged-cell input and paste results. These tokens cover those commands
    // without treating selection/formatting-only commands as value changes.
    'edit-cell-value',
    'update-cell-value',
    'replace-cell-value',
  ].some((token) => id.includes(token));
}
