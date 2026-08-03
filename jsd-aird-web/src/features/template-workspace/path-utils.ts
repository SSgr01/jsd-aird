export function getAtPath(root: Record<string, unknown>, path: string): unknown {
  const segments = path.split('/').filter(Boolean).map(decodeSegment);
  let current: unknown = root;
  for (const segment of segments) {
    if (current === null || typeof current !== 'object') {
      return undefined;
    }
    current = (current as Record<string, unknown>)[segment];
  }
  return current;
}

export function setAtPath(
  root: Record<string, unknown>,
  path: string,
  value: unknown,
): Record<string, unknown> {
  const copy = structuredClone(root);
  const segments = path.split('/').filter(Boolean).map(decodeSegment);
  let current = copy;
  segments.forEach((segment, index) => {
    if (index === segments.length - 1) {
      current[segment] = value;
      return;
    }
    const next = current[segment];
    if (next === null || typeof next !== 'object' || Array.isArray(next)) {
      current[segment] = {};
    }
    current = current[segment] as Record<string, unknown>;
  });
  return copy;
}

function decodeSegment(value: string) {
  return value.replaceAll('~1', '/').replaceAll('~0', '~');
}
