/** Excel format tokens outside quoted/escaped literals and colour/locale modifiers. */
export function numberFormatTokens(pattern: string) {
  return pattern.replace(/"[^"]*"|\\.|_.|\*./g, '').replace(/\[(?!(?:h+|m+|s+)\])[^\]]*\]/gi, '');
}

export function percentageFormat(pattern: string) {
  return numberFormatTokens(pattern).includes('%');
}

/** Convert a value typed in the workbench as displayed back to the cell's stored value. */
export function workbookCellInput(value: unknown, pattern: string): unknown {
  const tokens = numberFormatTokens(pattern).toLowerCase();
  const text = typeof value === 'string' ? value.trim() : undefined;
  const numberText = text?.replace(/[％%]\s*$/, '').trim();
  const numeric = typeof value === 'number' && Number.isFinite(value)
    ? value
    : numberText && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(numberText)
      ? Number(numberText)
      : undefined;
  if (percentageFormat(pattern)) return numeric === undefined ? value : numeric / 100;
  if (text) {
    const duration = /\[(?:h+|m+|s+)\]/.test(tokens);
    const clock = /h|s/.test(tokens) && !/[0#]/.test(tokens);
    const calendar = /y|d/.test(tokens) && !/[0#]/.test(tokens);
    const temporal = temporalCellInput(text, calendar, clock, duration);
    if (temporal !== undefined) return temporal;
  }
  // Explicit numeric formats should store a number. General/text formats retain
  // strings such as sample identifiers with leading zeroes.
  return numeric !== undefined && /[0#?]/.test(tokens) && !tokens.includes('@') ? numeric : value;
}

function temporalCellInput(text: string, calendar: boolean, clock: boolean, duration: boolean) {
  if (calendar) {
    const match = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?$/.exec(text);
    if (!match) return undefined;
    const year = Number(match[1]), month = Number(match[2]), day = Number(match[3]);
    const hour = Number(match[4] ?? 0), minute = Number(match[5] ?? 0), second = Number(match[6] ?? 0);
    const timestamp = Date.UTC(year, month - 1, day, hour, minute, second);
    const verified = new Date(timestamp);
    if (verified.getUTCFullYear() !== year || verified.getUTCMonth() !== month - 1
      || verified.getUTCDate() !== day || hour > 23 || minute > 59 || second > 59) return undefined;
    return (timestamp - Date.UTC(1899, 11, 30)) / 86400000;
  }
  if (clock || duration) {
    const match = /^(\d+):(\d{2})(?::(\d{2}))?$/.exec(text);
    if (!match) return undefined;
    const hour = Number(match[1]), minute = Number(match[2]), second = Number(match[3] ?? 0);
    if ((!duration && hour > 23) || minute > 59 || second > 59) return undefined;
    return (hour * 3600 + minute * 60 + second) / 86400;
  }
  return undefined;
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
