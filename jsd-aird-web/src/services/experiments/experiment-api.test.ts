import { actExperiment, confirmExperimentTemplateData, getExperiment, getExperimentPresentation, getExperimentTemplateDataReview, publishExperiment, saveExperiment } from './experiment-api';

const mocks=vi.hoisted(() => ({post:vi.fn(),get:vi.fn()}));
vi.mock('@/services/http/client',() => ({httpClient:{post:mocks.post,get:mocks.get},LARGE_PAYLOAD_TIMEOUT:0}));

beforeEach(() => {vi.clearAllMocks();mocks.post.mockResolvedValue({data:{data:{summary:{revision:2}}}});mocks.get.mockResolvedValue({data:{data:{summary:{revision:2}}}});});

it('waits for full multi-sheet workbooks and their review data when reopening',async () => {
  await getExperiment('id');
  await getExperimentTemplateDataReview('id');
  await getExperimentPresentation('id',2);
  expect(mocks.get).toHaveBeenNthCalledWith(1,'/api/v1/experiments/id/edit-model',{timeout:0});
  expect(mocks.get).toHaveBeenNthCalledWith(2,'/api/v1/experiments/id/data-review',{timeout:0});
  expect(mocks.get).toHaveBeenNthCalledWith(3,'/api/v1/experiments/id/presentation',{params:{revision:2},timeout:0});
});

it('waits for the saved workbook response',async () => {
  await saveExperiment('id',{revision:1,editModel:{}});
  expect(mocks.post).toHaveBeenCalledWith('/api/v1/experiments/id/draft',JSON.stringify({revision:1,editModel:{}}),{headers:{'Content-Type':'application/json'},timeout:0});
});

it('waits for confirmation and publication responses',async () => {
  await confirmExperimentTemplateData('id',1);
  await publishExperiment('id',2);
  expect(mocks.post).toHaveBeenNthCalledWith(1,'/api/v1/experiments/id/confirm-data',{revision:1},{timeout:0});
  expect(mocks.post).toHaveBeenNthCalledWith(2,'/api/v1/experiments/id/publish',{revision:2},{timeout:0});
});

it.each(['submit-review','return','approve'] as const)('waits for %s without retrying the command',async action => {
  await actExperiment('id',action,2,'本地回归测试');
  expect(mocks.post).toHaveBeenCalledExactlyOnceWith(`/api/v1/experiments/id/${action}`,{revision:2,comment:'本地回归测试'},{timeout:0});
});
