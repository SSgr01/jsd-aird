export function getAtPath(root: Record<string, unknown>, path: string): unknown {
  const segments = path.split('/').filter(Boolean).map(decodeSegment);
  return readSegments(root, segments);
}

export function setAtPath(
  root: Record<string, unknown>,
  path: string,
  value: unknown,
): Record<string, unknown> {
  const copy = structuredClone(root);
  const segments = path.split('/').filter(Boolean).map(decodeSegment);
  writeSegments(copy, segments, value);
  return copy;
}

function readSegments(current: unknown, segments: string[]): unknown {
  if (!segments.length) return current;
  const segment = segments[0];
  if (segment === undefined) return current;
  const tail = segments.slice(1);
  if (segment === '*') {
    if (!Array.isArray(current)) return [];
    return current.map((item) => readSegments(item, tail));
  }
  if (!isRecord(current)) return undefined;
  return readSegments(current[segment], tail);
}

function writeSegments(current: Record<string, unknown>, segments: string[], value: unknown) {
  const [segment, ...tail] = segments;
  if (!segment) return;
  if (!tail.length) {
    current[segment] = value;
    return;
  }

  if (tail[0] === '*') {
    const values: unknown[] = Array.isArray(value) ? unknownArray(value) : [value];
    const existing: unknown[] = Array.isArray(current[segment])
      ? unknownArray(current[segment])
      : [];
    const afterWildcard = tail.slice(1);
    const length = Math.max(existing.length, values.length);
    current[segment] = Array.from({ length }, (_, index) => {
      const record = isRecord(existing[index]) ? structuredClone(existing[index]) : {};
      if (index < values.length) {
        if (afterWildcard.length) writeSegments(record, afterWildcard, values[index]);
        else return values[index];
      }
      return record;
    });
    return;
  }

  const next = isRecord(current[segment]) ? current[segment] : {};
  current[segment] = next;
  writeSegments(next, tail, value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function unknownArray(value: unknown[]): unknown[] {
  return value;
}

function decodeSegment(value: string) {
  return value.replaceAll('~1', '/').replaceAll('~0', '~');
}
