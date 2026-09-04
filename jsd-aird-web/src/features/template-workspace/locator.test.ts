import { describe, expect, it } from 'vitest';

import {
  expandSemanticLabelRange,
  locatorLabelRange,
  locatorValueRange,
  mergeLocators,
  synchronizeLocatorCoordinates,
} from './locator';

describe('template locator normalization', () => {
  it('uses labelRange when the recognition result does not contain labelAddress', () => {
    const locator = mergeLocators({
      labelRange: 'A5:C5',
      address: 'D5:H5',
    });

    expect(locator.label).toEqual({ address: 'A5', range: 'A5:C5' });
    expect(locator.value).toEqual({ address: 'D5', range: 'D5:H5' });
    expect(locatorLabelRange(locator)).toBe('A5:C5');
    expect(locatorValueRange(locator)).toBe('D5:H5');
  });

  it('does not lose a field label when a sparse binding locator overrides it', () => {
    const locator = mergeLocators(
      { label: { address: 'A2', range: 'A2' }, value: { address: 'B2', range: 'B2' } },
      { address: 'C2' },
    );

    expect(locatorLabelRange(locator)).toBe('A2');
    expect(locatorValueRange(locator)).toBe('C2');
  });

  it('marks value-only locations unresolved instead of inventing a label', () => {
    const locator = mergeLocators({ address: 'B2' });

    expect(locator.label).toBeUndefined();
    expect(locator.source).toBe('UNRESOLVED');
    expect(locator.relation).toBe('UNRESOLVED');
  });

  it('extends a compound table label to include its method cell', () => {
    const locator = expandSemanticLabelRange(
      { labelRange: 'A6:C6', valueRange: 'E6:N6' },
      ['物性测试', '固含', '120℃×1h'],
    );

    expect(locatorLabelRange(locator)).toBe('A6:D6');
    expect(locatorValueRange(locator)).toBe('E6:N6');
  });

  it('does not expand a simple field label based only on a nearby value', () => {
    const locator = expandSemanticLabelRange(
      { labelRange: 'A2:C2', valueRange: 'E2:N2' },
      ['基本信息', '实验日期'],
    );

    expect(locatorLabelRange(locator)).toBe('A2:C2');
  });

  it('moves every value alias together when a field coordinate changes', () => {
    const locator = synchronizeLocatorCoordinates({
      address: 'B5', range: 'B5', valueRange: 'B5', logicalInputRange: 'B5',
      anchorAddress: 'B5', anchorRange: 'B5', value: { address: 'B5', range: 'B5' },
      labelAddress: 'A5', labelRange: 'A5',
    }, { address: 'C5' });

    expect(locatorValueRange(locator)).toBe('C5');
    expect(locator.address).toBe('C5');
    expect(locator.range).toBe('C5');
    expect(locator.valueRange).toBe('C5');
    expect(locator.logicalInputRange).toBe('C5');
    expect(locator.anchorAddress).toBe('C5');
    expect(locator.anchorRange).toBe('C5');
    expect(locator.valueAddress).toBe('C5');
    expect(locator.valueAnchor).toBe('C5');
    expect((locator.value as Record<string, unknown>).range).toBe('C5');
    expect(locatorLabelRange(locator)).toBe('A5');
  });

  it('moves label aliases without changing the value coordinate', () => {
    const locator = synchronizeLocatorCoordinates({
      address: 'B5', valueRange: 'B5', logicalInputRange: 'B5',
      labelAddress: 'A5', labelRange: 'A5',
    }, { labelAddress: 'C5' });

    expect(locatorLabelRange(locator)).toBe('C5');
    expect(locator.labelAddress).toBe('C5');
    expect(locator.labelRange).toBe('C5');
    expect(locator.labelAnchor).toBe('C5');
    expect((locator.label as Record<string, unknown>).address).toBe('C5');
    expect(locatorValueRange(locator)).toBe('B5');
  });
});
