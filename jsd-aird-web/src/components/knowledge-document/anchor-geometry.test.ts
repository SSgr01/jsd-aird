import { describe, expect, it } from 'vitest';

import type { SourceAnchor } from '@/services/knowledge';
import { normalizedAnchorBounds, normalizedAnchorContainsPoint, normalizedAnchorStyle } from './anchor-geometry';

describe('normalized anchor geometry', () => {
  it('converts a flat normalized polygon to percentage styles', () => {
    const anchor = region([0.618, 0.148, 0.917, 0.148, 0.917, 0.384, 0.618, 0.384]);

    expect(normalizedAnchorStyle(anchor)).toEqual({
      left: '61.8%',
      top: '14.8%',
      width: '29.9%',
      height: '23.6%',
    });
  });

  it('supports nested polygons and normalized point matching', () => {
    const anchor = region([[0.1, 0.2], [0.4, 0.2], [0.4, 0.6], [0.1, 0.6]]);

    const bounds = normalizedAnchorBounds(anchor);
    expect(bounds?.left).toBe(0.1);
    expect(bounds?.top).toBe(0.2);
    expect(bounds?.width).toBeCloseTo(0.3);
    expect(bounds?.height).toBeCloseTo(0.4);
    expect(normalizedAnchorContainsPoint(anchor, 0.25, 0.4)).toBe(true);
    expect(normalizedAnchorContainsPoint(anchor, 0.5, 0.4)).toBe(false);
  });

  it.each([
    region([]),
    region([0.1, 0.2, 0.4]),
    region([0.1, 0.2, Number.NaN, 0.4]),
    region([-0.1, 0.2, 0.4, 0.6]),
    region([0.1, 0.2, 0.1, 0.6]),
    { version: 1, kind: 'page', page: 1 } satisfies SourceAnchor,
  ])('does not render invalid or non-region anchors', (anchor) => {
    expect(normalizedAnchorStyle(anchor)).toBeUndefined();
  });
});

function region(polygon: number[] | number[][]): SourceAnchor {
  return { version: 1, kind: 'page_region', page: 1, polygon };
}
