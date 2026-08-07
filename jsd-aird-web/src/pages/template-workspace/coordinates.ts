export function normalizeAddress(value: unknown) {
  return (typeof value === 'string' ? value : '').trim().replaceAll('$', '').toUpperCase();
}

export function validateAddress(value: string, singleCell: boolean) {
  if (!value) return undefined;
  const cell = '[A-Z]{1,4}[1-9][0-9]*';
  const pattern = new RegExp(singleCell ? `^${cell}$` : `^${cell}(?::${cell})?$`);
  if (pattern.test(value)) return undefined;
  return singleCell ? '请输入单个单元格，例如 A2' : '请输入单元格或连续范围，例如 B2 或 B7:D10';
}
