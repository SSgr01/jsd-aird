import test from 'node:test';
import assert from 'node:assert/strict';
import {businessError,deliveryError} from '../../enterprise-plugin/src/business-error.mjs';

test('tool schema failures expose repairable paths without submitted values', async()=>{
  const response = new Response(JSON.stringify({detail:{code:'INVALID_TOOL_ARGUMENTS',path:['claims',0,'qualifiers'],
    rule:'type',expected:'object',submitted:'private input'}}),{status:422});
  const error=await businessError(response);
  assert.match(error.message,/qualifiers/);assert.match(error.message,/object/);
  assert.doesNotMatch(error.message,/private input/);
});

test('internal failures never forward server bodies or stack traces', async()=>{
  const error=await businessError(new Response(JSON.stringify({detail:'connection-string secret'}),{status:500}));
  assert.equal(error.message,'ENTERPRISE_GATE_500');
});

test('native turn failures retain a useful business or budget diagnosis', async()=>{
  const budget=await businessError(new Response(JSON.stringify({detail:'TOKEN_BUDGET_EXCEEDED'}),{status:409}));
  assert.equal(budget.failure.code,'TOKEN_BUDGET_EXCEEDED');
  assert.match(budget.failure.message,/预算/);
  const delivery=deliveryError({completion:{gaps:['CITATION_INVALID']}});
  assert.equal(delivery.failure.code,'CITATION_INVALID');
  assert.match(delivery.failure.message,/引用/);
});
