import test from 'node:test';
import assert from 'node:assert/strict';
import {standardPlugins} from '../src/config.mjs';
import {ToolResultPruner} from '../../../vendor/deepseek-harness/packages/compaction/compaction-tool-result-pruner/lib/index.js';
import {renderModelResult} from '../../enterprise-plugin/src/model-view.mjs';

test('fresh bounded business JSON survives the actual native pruning pass intact',()=>{
  const plugins=standardPlugins({plugin:'G:/enterprise',model:{provider:'qwen',id:'configured'}});
  const config=plugins.find(row=>row.id==='compaction').config.find(row=>row.id==='tool-result-pruner').config;
  const pruner={config,measureContent:ToolResultPruner.prototype.measureContent};
  const content=renderModelResult({briefing_progress:{complete:false,next_request:{}},
    candidates:[{text:'原值 🧪 '.repeat(6000),source:{resource_type:'EXPERIMENT'},ref:{type:'EXPERIMENT',id:'fixed'}}],
    next_action:'继续读取下一批正文'},45000);
  assert.ok(Array.from(content[0].text).length>8192);
  assert.equal(ToolResultPruner.prototype.pruneContent.call(pruner,content),null);
  assert.doesNotThrow(()=>JSON.parse(content[0].text));
  assert.equal(ToolResultPruner.prototype.pruneContent.call(pruner,[{type:'text',text:'a'.repeat(45000)}]),null);
  assert.notEqual(ToolResultPruner.prototype.pruneContent.call(pruner,[{type:'text',text:'a'.repeat(45001)}]),null);
});
