/* eslint-disable @typescript-eslint/unbound-method -- These Axios methods are replaced by Vitest mocks. */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { httpClient } from '@/services/http/client';
import { saveExperiment } from './experiments/experiment-api';
import { productionOrderRecordApi } from './production-orders/production-upload-api';

vi.mock('@/services/http/client', () => ({ httpClient: { post: vi.fn(), put: vi.fn() } }));
beforeEach(() => { vi.clearAllMocks(); vi.mocked(httpClient.post).mockResolvedValue({ data: { data: {} } }); vi.mocked(httpClient.put).mockResolvedValue({ data: { data: {} } }); });
describe('workspace JSON writes capture immutable bodies before HTTP interceptors', () => {
  it('serializes the experiment document once and preserves the captured revision', async () => {
    const input = { revision: 4, editModel: { documentSnapshot: { body: { dataStream: 'before' } } } };
    const stringify = vi.spyOn(JSON, 'stringify');
    const saving = saveExperiment('e', input); expect(stringify).toHaveBeenCalledTimes(1); stringify.mockRestore();
    input.editModel.documentSnapshot.body.dataStream = 'after'; input.revision = 5;
    await saving;
    const [, body, config] = vi.mocked(httpClient.post).mock.calls[0]!;
    expect(JSON.parse(body as string)).toMatchObject({ revision: 4, editModel: { documentSnapshot: { body: { dataStream: 'before' } } } });
    expect(config?.headers?.['Content-Type']).toBe('application/json');
  });
  it('serializes a production batch once without changing its REST envelope', async () => {
    const input = { records: [{ id: 'r', lockVersion: 3, workbookSnapshot: { sheets: { s: { value: 'before' } } } }] };
    const stringify = vi.spyOn(JSON, 'stringify');
    const saving = productionOrderRecordApi.saveBatch(input); expect(stringify).toHaveBeenCalledTimes(1); stringify.mockRestore();
    input.records[0]!.workbookSnapshot.sheets.s.value = 'after';
    await saving;
    const [, body, config] = vi.mocked(httpClient.put).mock.calls[0]!;
    expect(JSON.parse(body as string)).toEqual({ records: [{ id: 'r', lockVersion: 3, workbookSnapshot: { sheets: { s: { value: 'before' } } } }] });
    expect(config?.headers?.['Content-Type']).toBe('application/json');
  });
});
