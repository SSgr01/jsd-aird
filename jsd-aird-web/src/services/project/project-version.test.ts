import { httpClient } from '@/services/http/client';
import { deleteProjects, updateProject, type ProjectInput } from './project-api';
import { HttpError } from '@/services/http/errors';

vi.mock('@/services/http/client',()=>({httpClient:{put:vi.fn(),delete:vi.fn()},LARGE_PAYLOAD_TIMEOUT:0}));
beforeEach(()=>{vi.clearAllMocks();vi.mocked(httpClient.put).mockResolvedValue({});vi.mocked(httpClient.delete).mockResolvedValue({});});

it('sends the version the editor originally read, including version zero',async()=>{
  await updateProject('one',{name:'draft',version:0});
  expect(httpClient.put).toHaveBeenCalledExactlyOnceWith('/api/v1/projects/one',{name:'draft',version:0});
});
it('rejects an unversioned update before sending a write',async()=>{
  await expect(updateProject('one',{name:'draft'} as ProjectInput & {version:number})).rejects.toMatchObject({code:'VERSION_REQUIRED'});
  expect(httpClient.put).not.toHaveBeenCalled();
});
it('deletes using the selected versions without silently fetching newer versions',async()=>{
  await deleteProjects([{id:'one',version:12},{id:'two',version:0}]);
  expect(httpClient.delete).toHaveBeenCalledWith('/api/v1/projects/one',{params:{version:12}});
  expect(httpClient.delete).toHaveBeenCalledWith('/api/v1/projects/two',{params:{version:0}});
});
it('validates the entire selection before starting any batch delete',async()=>{
  await expect(deleteProjects([{id:'one',version:12},{id:'two',version:NaN}])).rejects.toMatchObject({code:'VERSION_REQUIRED'});
  expect(httpClient.delete).not.toHaveBeenCalled();
});
it('waits for settled deletes and never retries a revision conflict',async()=>{
  vi.mocked(httpClient.delete).mockRejectedValue(new HttpError('数据已变更','OPTIMISTIC_LOCK_CONFLICT',409));
  await expect(deleteProjects([{id:'one',version:12}])).rejects.toMatchObject({status:409});
  expect(httpClient.delete).toHaveBeenCalledOnce();
});
