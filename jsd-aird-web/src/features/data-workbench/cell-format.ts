/** Excel format tokens outside quoted/escaped literals and colour/locale modifiers. */
export function numberFormatTokens(pattern: string) {
  return pattern.replace(/"[^"]*"|\\.|_.|\*./g, '').replace(/\[(?!(?:h+|m+|s+)\])[^\]]*\]/gi, '');
}

export function percentageFormat(pattern: string) {
  return numberFormatTokens(pattern).includes('%');
}

/** Match the sheet's temporal meaning without interpreting a duration as a calendar date. */
export function workbookCellValue(raw: unknown, type: string, pattern: string): unknown {
  if (typeof raw !== 'number' || !Number.isFinite(raw)) return raw;
  const tokens = numberFormatTokens(pattern).toLowerCase();
  const kind = type.toLowerCase();
  const duration = /\[(?:h+|m+|s+)\]/.test(tokens);
  const clock = /h|s/.test(tokens) && !/[0#]/.test(tokens);
  const calendar = /y|d/.test(tokens) && !/[0#]/.test(tokens);
  const time = kind === 'time' || clock;
  if (duration || (kind === 'duration' && clock)) {
    const seconds = Math.round(Math.abs(raw) * 86400);
    return `${raw < 0 ? '-' : ''}${Math.floor(seconds / 3600)}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
  }
  if ((/^(date|datetime)$/.test(kind) || calendar || time) && raw >= 0 && raw <= 2958465) {
    const date = new Date(Date.UTC(1899, 11, 30) + Math.round(raw * 86400000));
    if (!Number.isFinite(date.getTime())) return raw;
    const iso = date.toISOString();
    if (kind === 'datetime' || calendar && clock) return `${iso.slice(0, 10)} ${iso.slice(11, 19)}`;
    if (kind === 'time' || time && !calendar && !/^(date|datetime)$/.test(kind)) return iso.slice(11, 19);
    return iso.slice(0, 10);
  }
  return percentageFormat(pattern) ? Number((raw * 100).toPrecision(12)) : raw;
}
