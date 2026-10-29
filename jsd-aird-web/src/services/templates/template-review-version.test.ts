import { templateApi } from './template-api';

const post = vi.hoisted(() => vi.fn());
vi.mock('@/services/http/client',()=>({httpClient:{post},LARGE_PAYLOAD_TIMEOUT:0}));
vi.mock('@/services/files/file-api',()=>({downloadBlob:vi.fn(),triggerNativeDownload:vi.fn()}));
beforeEach(()=>{vi.clearAllMocks();post.mockResolvedValue({data:{data:{}}});});
const versions={workspaceVersion:12,reviewVersion:5};

it('submits the exact workspace and review revisions the user saw',async()=>{
  await templateApi.submitReview('one',versions,true);
  expect(post).toHaveBeenCalledExactlyOnceWith('/api/v1/template-versions/one/review/submit',{...versions,dataCleanupConfirmed:true});
});
it('approval and rejection carry both revisions',async()=>{
  await templateApi.approveReview('one',versions);await templateApi.rejectReview('one',versions,'修改');
  expect(post).toHaveBeenCalledWith('/api/v1/template-versions/one/review/approve',{...versions,comment:undefined});
  expect(post).toHaveBeenCalledWith('/api/v1/template-versions/one/review/reject',{...versions,reason:'修改'});
});
it('does not approve through an old server response without the review revision',async()=>{
  await expect(templateApi.approveReview('one',{workspaceVersion:12,reviewVersion:undefined} as unknown as typeof versions)).rejects.toMatchObject({code:'VERSION_REQUIRED'});
  expect(post).not.toHaveBeenCalled();
});
