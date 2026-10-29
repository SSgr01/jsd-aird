import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { sha256 } from 'k6/crypto';

const base = __ENV.BASE_URL || 'http://api:8080';
const run = __ENV.AIRD_CAPACITY_RUN_ID || '';
if (!/^aird-capacity-[a-z0-9-]{8,48}$/.test(run) || base !== 'http://api:8080') {
  throw new Error('Only the owned Compose API is allowed; run the lab on the independent server itself');
}
const fixtures = JSON.parse(open('/results/fixtures.json'));
if (fixtures.runId !== run) throw new Error('Fixture ownership mismatch');
const model = __ENV.MODEL || 'closed';
const profile = __ENV.PROFILE || 'read';
const level = Number(__ENV.LEVEL || 20);
const stageKey = sha256(`${run}/${__ENV.STAGE_ID || `${profile}-${model}-${level}`}`, 'hex').slice(0, 16);
const duration = __ENV.DURATION || '5m';
const warmup = Number(__ENV.WARMUP_SECONDS ?? 120);
const durationMatch = duration.match(/^(\d+)([smh])$/);
if (!durationMatch) throw new Error('Invalid stage duration');
const measuredSeconds = Number(durationMatch[1]) * { s: 1, m: 60, h: 3600 }[durationMatch[2]];
const totalDuration = `${measuredSeconds + warmup}s`;
const readMs = new Trend('business_read_ms', true);
const writeMs = new Trend('business_write_ms', true);
const failure = new Rate('capacity_failure');
const unexpected = new Rate('unexpected_error');
const busy = new Counter('busy_rejected');
const conflicts = new Counter('version_conflicts');
const completed = new Counter('business_completed');
const attempted = new Counter('business_attempted');
const thresholds = { unexpected_error: ['rate<0.005'], capacity_failure: ['rate<0.005'],
  dropped_iterations: ['count==0'] };
if (profile !== 'workspace') {
  thresholds.business_read_ms = ['p(95)<=800', 'p(99)<=2000'];
  if (profile !== 'read' && profile !== 'poll') thresholds.business_write_ms = ['p(95)<=1500', 'p(99)<=3000'];
}
// Contention/fault rejections remain in the raw data; they are never counted as stable capacity passes.
export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(75)', 'p(95)', 'p(99)'],
  setupTimeout: '30s', gracefulStop: '15s', thresholds,
  scenarios: { measured: model === 'open'
    ? { executor: 'constant-arrival-rate', rate: level, timeUnit: '1s', duration: totalDuration,
      preAllocatedVUs: Math.min(400, Math.max(20, level * 2)), maxVUs: 400 }
    : { executor: 'constant-vus', vus: level, duration: totalDuration } },
  tags: { run, model, profile, level: String(level) },
};

let authenticated = false;
let csrf;
let ownProject;
let projectState;
let reviewState;
let workspaceState;
let publicationState;
function parameters(write = false) {
  return { timeout: profile === 'workspace' ? '125s' : '15s', headers: {
    ...(write ? { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf } : {}),
    'X-Request-Id': `${stageKey}-${__VU}-${__ITER}`,
  }, tags: { phase: 'business' } };
}
function data(response) {
  try { return response.json().data; } catch { return undefined; }
}
function record(response, write = false, contention = false) {
  if (exec.instance.currentTestRunDuration < warmup * 1000) return response.status >= 200 && response.status < 300;
  attempted.add(1);
  const success = response.status >= 200 && response.status < 300;
  const conflict = response.status === 409;
  if (response.status === 503) busy.add(1);
  if (conflict) conflicts.add(1);
  failure.add(!success);
  unexpected.add(!success && !(contention && conflict) && response.status !== 503 && response.status !== 504);
  (write ? writeMs : readMs).add(response.timings.duration);
  if (success) completed.add(1);
  return success;
}
function login() {
  if (authenticated) return;
  const initial = http.get(`${base}/api/v1/auth/csrf`, { tags: { phase: 'authentication' } });
  csrf = data(initial)?.token;
  if (!csrf) fail('CSRF initialization failed');
  const response = http.post(`${base}/api/v1/auth/login`, JSON.stringify({
    username: `capacity-${(__VU - 1) % 400}`, password: __ENV.CAPACITY_PASSWORD,
  }), { headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf }, tags: { phase: 'authentication' } });
  if (!check(response, { 'real cookie login succeeded': r => r.status === 200 })) fail('Isolated login failed');
  authenticated = true;
}
const readPaths = [
  '/api/v1/projects?page=1&size=20', '/api/v1/experiments?page=1&size=20',
  '/api/v1/templates?page=1&size=20', '/api/v1/knowledge/documents?page=1&size=20',
  '/api/v1/data/sources?page=1&size=20', '/api/v1/inventory/balances?page=1&size=20',
  '/api/v1/quality/records?type=record&page=1&size=20', '/api/v1/production-orders?page=1&size=20',
  '/api/v1/comprehensive-reports?page=1&size=20', '/api/v1/spc/charts?page=1&size=20',
];
function read() {
  const response = http.get(base + readPaths[exec.scenario.iterationInTest % readPaths.length], parameters());
  record(response);
}
function projectWrite(hot = false) {
  if (!hot && !ownProject) {
    const created = http.post(`${base}/api/v1/projects`, JSON.stringify({
      projectCode: `CAP-W-${stageKey}-${__VU}`, name: `隔离写入 ${__VU}`, priority: 'MEDIUM', status: 'NOT_STARTED',
      teamSize: 0, customFields: {}, teamMembers: [],
    }), parameters(true));
    if (!record(created, true)) return;
    ownProject = data(created)?.id;
    if (!ownProject) fail('Project create response lacks id');
    return;
  }
  const id = hot ? fixtures.projects[0] : ownProject;
  if (!projectState) {
    const current = http.get(`${base}/api/v1/projects/${id}`, parameters());
    if (record(current)) projectState = data(current);
    return;
  }
  const project = projectState;
  if (typeof project?.version !== 'number') fail('Project fixture lacks a real version');
  const response = http.put(`${base}/api/v1/projects/${id}`, JSON.stringify({
    ...project, name: `隔离修改 ${__VU}-${__ITER}`, version: project.version,
  }), parameters(true));
  if (record(response, true, hot) && !hot) projectState = { ...project, version: project.version + 1 };
  else projectState = undefined;
}
function review() {
  // Consume one existing pending review cycle; subsequent iterations re-read its latest revision.
  const id = fixtures.experiments[1];
  if (!reviewState) {
    const current = http.get(`${base}/api/v1/experiments/${id}`, parameters());
    if (record(current)) reviewState = data(current);
    return;
  }
  const summary = reviewState?.summary;
  if (!summary || typeof summary.revision !== 'number') fail('Review fixture lacks summary/revision');
  const action = __VU % 2 === 0 ? 'approve' : 'return';
  const response = http.post(`${base}/api/v1/experiments/${id}/${action}`,
    JSON.stringify({ revision: summary.revision, comment: '隔离竞争测试' }), parameters(true));
  record(response, true, true);
  reviewState = undefined;
}
function workspace() {
  const id = fixtures.templates[(__VU - 1) % fixtures.templates.length];
  if (!workspaceState) {
    const response = http.get(`${base}/api/v1/template-versions/${id}/edit-model`, parameters());
    if (record(response)) workspaceState = data(response);
    return;
  }
  const value = workspaceState;
  const response = http.put(`${base}/api/v1/template-versions/${id}/draft`, JSON.stringify({
    lockVersion: value.lockVersion, baseWorkspaceHash: value.workspaceHash, schema: value.schema,
    mapping: value.mapping, data: value.data, snapshotFileId: value.snapshotFileId, snapshotHash: value.snapshotHash,
    editorAppVersion: value.editorAppVersion, pluginManifest: '[]', snapshotFormatVersion: value.snapshotFormatVersion,
    clientCommandSummary: '隔离工作区保存', idempotencyKey: `${stageKey}-${__VU}-${__ITER}`,
    bindingValues: [], recognitionActions: [], qualityActions: [], structureOperations: [],
  }), parameters(true));
  record(response, true, true); workspaceState = undefined;
}
function publication() {
  const index = (__VU - 1) % fixtures.data.length; const id = fixtures.data[index];
  const path = `${base}/api/v1/data/sources/${id}/publication`;
  if (!publicationState) {
    const response = http.get(path, parameters());
    if (record(response)) publicationState = data(response);
    return;
  }
  const value = publicationState;
  const action = { DRAFT: 'confirm', RETURNED: 'confirm', CONFIRMED: 'submit', PENDING_REVIEW: 'approve', APPROVED: 'publish', PUBLISHED: 'revise' }[value.status];
  if (!action) fail('Publication fixture reached an unsupported state');
  const response = http.post(`${path}/${action}`, JSON.stringify({ revision: value.revision,
    reviewerId: fixtures.reviewers[(__VU - 1) % fixtures.reviewers.length],
    snapshotId: value.confirmedSnapshot?.id, approved: true, comment: '隔离发布校验',
  }), parameters(true));
  publicationState = record(response, true, true) ? data(response) : undefined;
}
function inventory() {
  const response = http.post(`${base}/api/v1/inventory/transactions`, JSON.stringify({
    productId: fixtures.inventory[0], scope: 'RND', direction: __ITER % 2 === 0 ? 'INBOUND' : 'OUTBOUND',
    reason: '隔离容量测试', quantityKg: 1, documentNo: `${stageKey}-${__VU}-${__ITER}`,
    businessType: 'MANUAL', actorName: '隔离用户',
  }), { ...parameters(true), headers: { ...parameters(true).headers, 'Idempotency-Key': `${stageKey}-${__VU}-${__ITER}` } });
  record(response, true, true);
}
export default function () {
  login();
  if (profile === 'read') read();
  else if (profile === 'poll') {
    // Each visible screen checks once per 30s. Login/first read is immediate; first repeat is jittered.
    for (const path of readPaths.slice(0, 6)) record(http.get(base + path, parameters()));
    sleep(__ITER === 0 ? 25 + Math.random() * 5 : 30);
  } else if (profile === 'write') projectWrite();
  else if (profile === 'hotspot') projectWrite(true);
  else if (profile === 'review') review();
  else if (profile === 'inventory') inventory();
  else if (profile === 'workspace') workspace();
  else if (profile === 'publication') publication();
  else if (profile === 'mixed') { if (Math.random() < 0.8) read(); else projectWrite(); }
  else throw new Error(`Unknown profile: ${profile}`);
  if (model === 'closed' && profile !== 'poll') sleep(0.05);
}
export function handleSummary(summary) {
  return { [__ENV.SUMMARY_FILE || '/results/summary.json']: JSON.stringify({
    runId: run, profile, model, level, duration, activeVUs: summary.metrics.vus?.values,
    requestedBusinessRequestArrivalRate: model === 'open' ? level : null, warmupSeconds: warmup, metrics: summary.metrics,
    generatorLimit: { cpu: 1, memoryBytes: 536870912, maxVUs: 400 },
  }, null, 2) };
}
