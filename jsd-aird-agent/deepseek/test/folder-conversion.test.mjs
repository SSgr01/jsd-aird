import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,rm,readFile,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {FolderConversionRuntime,folderNativeTools,isFolderSession} from '../../enterprise-plugin/src/folder-conversion.mjs';
import {BackgroundHosts} from '../src/background-hosts.mjs';

test('folder dispatch uses stable native inbox identities and binds the experiment expert',async t=>{
  const root=await mkdtemp(join(tmpdir(),'folder-runtime-'));t.after(()=>rm(root,{recursive:true,force:true}));await mkdir(join(root,'default'));
  const ids=['11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222'];
  const items=ids.map(id=>({id,session_id:'folder-'+id})),prompts=[],created=[],bindings=[];
  const runtime=new FolderConversionRuntime({workspaceRegistry:{async create(){return {id:'workspace'};}},sessionController:{
    async projections(){return undefined;},async create(body){created.push(body);},async prompt(body,signal){signal.throwIfAborted();prompts.push(body);},
  }},{executionRoot:root,withBatch:async(id,fn)=>{assert.equal(id,'batch');return fn();},business:async(action,args)=>{
    if(action==='folder-dispatch')return {batch_id:'batch',items};
    bindings.push(args);return {expert:null};
  }});
  await Promise.all([runtime.sync('batch'),runtime.sync('batch')]);
  assert.equal(prompts.length,2);assert.equal(created.length,2);
  assert.equal(bindings.filter(b=>b.action==='bind').length,2);
  assert.ok(prompts.every(p=>p.requestId===p.sessionId && p.content[0].text.includes('严禁 artifact_save')));
  assert.ok(items.every(item=>isFolderSession(item.session_id)));
  for(const name of ['present','artifact_save','experiment_conversion','web_fetch'])assert.equal(folderNativeTools.has(name),false);
});

test('folder source materialization hides binary payload and omits cross-file recipes',async t=>{
  const root=await mkdtemp(join(tmpdir(),'folder-source-'));t.after(()=>rm(root,{recursive:true,force:true}));
  const bytes=Buffer.from('source'),hash=createHash('sha256').update(bytes).digest('hex'),published=[];
  const ctx={workingDirectory:{async ensure(){return root;}},fs:{
    async resolve(path){return {targetKey:join(root,path)};},async publish(path,bytes){published.push(path);await mkdir(join(path,'..'),{recursive:true});await writeFile(path,bytes);},
  }};
  const runtime=new FolderConversionRuntime(ctx,{});
  const result=await runtime.materialize({agent:{}},{folder_source:true,name:'记录[1].docx',base64:bytes.toString('base64'),working_sha256:hash,
    templates:{test:bytes.toString('base64'),synthesis:bytes.toString('base64')}});
  assert.equal(result.base64,undefined);assert.equal(result.templates,undefined);
  assert.deepEqual(await readFile(join(root,result.source_path)),bytes);
  assert.ok(published.every(path=>!path.includes('recipes')));
  await assert.rejects(()=>runtime.materialize({agent:{}},{folder_source:true,name:'../../secret.docx',base64:bytes.toString('base64'),working_sha256:hash}),/SOURCE_NAME/);
});

test('background folder recovery does not replace research briefing identity',async t=>{
  const calls=[];
  t.mock.method(globalThis,'fetch',async(url,request)=>{
    if(url.endsWith('/folder-owners'))return Response.json({owners:[{id:'batch'}]});
    if(url.endsWith('/folder-ticket'))return Response.json({automation_grant_id:'batch',scope:'folder-conversion'});
    return Response.json({owners:[]});
  });
  const hosts={async ensure(identity,options){calls.push(['ensure',identity.scope,options.background]);return {};},
    async call(host,action,body){calls.push([action,body.batch_id]);},async expireBackground(){}};
  await new BackgroundHosts({controlUrl:'http://control',secret:'secret'},hosts).refresh();
  assert.deepEqual(calls,[['ensure','folder-conversion',true],['folder-sync','batch']]);
});
