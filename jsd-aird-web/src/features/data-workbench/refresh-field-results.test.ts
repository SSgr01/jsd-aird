import { refreshFieldResults } from './refresh-field-results';
import type { DataFieldValueView, DataRow } from '@/services/data/data-api';

it('refreshes corrected and calculated values using record, binding and path identities', () => {
  const field = { recordId: 'r', bindingId: 'b', valuePath: '/value', effectiveValue: 1, address: 'C4' } as DataFieldValueView;
  const row = { id: 'r', status: 'VALID', sheetId: 's', rowNumber: 4, rawValues: {}, normalizedValues: { value: { bindingId: 'b', valuePath: '/value', normalizedValue: 2 } },
    correctedValues: { value: { correctedValue: 0 } } } as DataRow;
  expect(refreshFieldResults([field], [row])[0]).toMatchObject({ effectiveValue: 0, address: 'C4' });
  expect(field.effectiveValue).toBe(1);
  expect(refreshFieldResults([field], [{ ...row, id: 'other' }])[0]).toBe(field);
  expect(refreshFieldResults([field], [{ ...row, normalizedValues: { wrong: { bindingId: 'b', valuePath: '/other', normalizedValue: 9 } } }])[0]).toBe(field);
  expect(refreshFieldResults([field], [{ ...row, correctedValues: {} }])[0]?.effectiveValue).toBe(2);
});
