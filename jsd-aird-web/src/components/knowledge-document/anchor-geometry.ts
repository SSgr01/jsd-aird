import type { SourceAnchor } from '@/services/knowledge';

export interface NormalizedAnchorBounds {
  left: number;
  top: number;
  width: number;
  height: number;
}

export function normalizedAnchorBounds(anchor: SourceAnchor): NormalizedAnchorBounds | undefined {
  if (anchor.kind !== 'page_region') return undefined;
  const values = (anchor.polygon ?? []).flat();
  if (values.length < 4 || values.length % 2 !== 0 || values.some((value) => !Number.isFinite(value) || value < 0 || value > 1)) return undefined;

  const xs = values.filter((_, index) => index % 2 === 0);
  const ys = values.filter((_, index) => index % 2 === 1);
  const left = Math.min(...xs);
  const top = Math.min(...ys);
  const right = Math.max(...xs);
  const bottom = Math.max(...ys);
  if (right <= left || bottom <= top) return undefined;

  return { left, top, width: right - left, height: bottom - top };
}

export function normalizedAnchorStyle(anchor: SourceAnchor) {
  const bounds = normalizedAnchorBounds(anchor);
  if (!bounds) return undefined;
  return {
    left: percentage(bounds.left),
    top: percentage(bounds.top),
    width: percentage(bounds.width),
    height: percentage(bounds.height),
  };
}

export function normalizedAnchorContainsPoint(anchor: SourceAnchor, x: number, y: number) {
  const bounds = normalizedAnchorBounds(anchor);
  if (!bounds) return false;
  return x >= bounds.left && x <= bounds.left + bounds.width && y >= bounds.top && y <= bounds.top + bounds.height;
}

function percentage(value: number) {
  return `${Number((value * 100).toFixed(6))}%`;
}
