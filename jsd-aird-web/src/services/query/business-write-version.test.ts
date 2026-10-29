import { beforeEach, expect, it, vi } from 'vitest';

import { dataApi } from '@/services/data/data-api';
import { productionOrderApi } from '@/services/production-orders/production-order-api';
import { productionUploadApi } from '@/services/production-orders/production-upload-api';
import { qualityApi } from '@/services/quality/quality-api';
import { knowledgeApi } from '@/services/knowledge/knowledge-api';
import { projectResourceApi } from '@/services/project/project-resource-api';
import { HttpError } from '@/services/http/errors';

const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }));
vi.mock('@/services/http/client', () => ({ httpClient: mocks, LARGE_PAYLOAD_TIMEOUT: 1000 }));
const revision = 'a'.repeat(64);
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(mocks.post).mockResolvedValue({ data: { data: { revision: 'b'.repeat(64) } } });
  vi.mocked(mocks.put).mockResolvedValue({ data: { data: { revision: 'b'.repeat(64) } } });
  vi.mocked(mocks.delete).mockResolvedValue({ data: { data: { revision: 'b'.repeat(64) } } });
});

it('submits and deletes both production order types using the original numeric version including zero', async () => {
  await productionOrderApi.submit('p',0);
  await productionUploadApi.delete('u',0);
  expect(mocks.post).toHaveBeenCalledWith('/api/v1/production-orders/p/submit',{lockVersion:0});
  expect(mocks.delete).toHaveBeenCalledWith('/api/v1/production-uploads/u',{params:{lockVersion:0}});
  expect(mocks.get).not.toHaveBeenCalled();
});

it('keeps version information on batch moves, deletions and publication', async () => {
  const records=[{id:'q',lockVersion:0}];
  await qualityApi.deleteRecords(records);
  await qualityApi.publishRecord('q',0);
  await knowledgeApi.batchMove([{id:'k',revision}], 'category');
  expect(mocks.post).toHaveBeenCalledWith('/api/v1/quality/records/delete',{records});
  expect(mocks.post).toHaveBeenCalledWith('/api/v1/quality/records/q/publish',{revision:0});
  expect(mocks.post).toHaveBeenCalledWith('/api/v1/knowledge/documents/batch/move',{documents:[{id:'k',revision}],categoryId:'category'});
});

it('returns the owned revision for sequential corrections and preserves the relation baseline', async () => {
  const saved=await dataApi.correctValue('j','r',{revision,bindingId:'b',valuePath:'',correctedValue:3,reason:'test'});
  await dataApi.saveMappings('j',saved.revision,[]);
  await dataApi.hideJob('j',revision);
  await projectResourceApi.replaceLinks('DATA_IMPORT_JOB','j',[{projectId:'new'}],[{projectId:'old'}]);
  expect(mocks.put).toHaveBeenCalledWith('/api/v1/data/import-jobs/j/mappings',{revision:'b'.repeat(64),items:[]});
  expect(mocks.delete).toHaveBeenCalledWith('/api/v1/data/import-jobs/j',{params:{revision}});
  expect(mocks.put).toHaveBeenCalledWith('/api/v1/project-resource-links/DATA_IMPORT_JOB/j',{targets:[{projectId:'new',stageId:undefined,taskId:undefined}],expectedTargets:[{projectId:'old',stageId:undefined,taskId:undefined}]});
});

it('does not refetch a new version or repeat a conflicted business write', async () => {
  vi.mocked(mocks.put).mockRejectedValue(new HttpError('conflict','OPTIMISTIC_LOCK_CONFLICT',409));
  await expect(dataApi.saveMappings('j',revision,[])).rejects.toMatchObject({status:409});
  expect(mocks.put).toHaveBeenCalledOnce();
  expect(mocks.get).not.toHaveBeenCalled();
});
