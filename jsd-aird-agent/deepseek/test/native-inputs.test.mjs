import test from 'node:test';
import assert from 'node:assert/strict';
import { checkRpc, checkUpload, nativeFileSession } from '../src/rpc-policy.mjs';

test('native appearance and font preferences persist through the bounded settings gate', async () => {
  for (const method of ['update','mutate']) {
    const send = patch => checkRpc('settings/'+method,{type:'client-request',method:'settings/'+method,
      payload:{args:{ns:'ui-theme',...(method === 'update' ? {patch} : {
        ops:Object.entries(patch).map(([key,value])=>({op:'set',path:[key],value})),
      })}}}, {identity:{role:'READER'}});
    for (const preference of ['light','dark','system']) await send({preference});
    await send({fontSize:18,codeFontSize:12,terminalFontSize:14,textFontFamily:'Microsoft YaHei UI',codeFontFamily:'',terminalFontFamily:'monospace'});
    for (const patch of [{preference:'external-theme'},{fontSize:23},{fontSize:14.5},{codeFontSize:17},
      {terminalFontSize:9},{textFontFamily:[]},{textFontFamily:'a'.repeat(1001)},
      {preference:'dark',apiKey:'unexpected'},{tokens:{'--unsafe':'value'}}])
      await assert.rejects(send(patch),/RPC_DENIED/);
  }
});

test('native composer can load its command catalog and invoke composed commands', async () => {
  const request = method => ({type:'client-request',method,payload:{args:{agent:'session-native'}}});
  for (const method of ['commands/list','commands/execute']) {
    await checkRpc(method,request(method),{});
    await assert.rejects(checkRpc(method,request('commands/register'),{}),/RPC_ENVELOPE_INVALID/);
  }
  for (const method of ['commands/register','commands/install','terminal/execute','plugins/install']) {
    await assert.rejects(checkRpc(method,request(method),{}),/RPC_DENIED/);
  }
});

test('prompt admits native file receipts, rejects direct attachment authority', async () => {
  const request = content => ({type:'client-request',method:'session/prompt',payload:{args:{request:{content}}}});
  await checkRpc('session/prompt', request([{type:'file',receiptId:'native-receipt'}]), {});
  await assert.rejects(checkRpc('session/prompt', request([{type:'file',attachment:{attachmentId:'foreign'}}]), {}), /NATIVE_UPLOAD_RECEIPT_REQUIRED/);
  await assert.rejects(checkRpc('session/prompt', request([{type:'file',receiptId:'valid',attachment:{attachmentId:'foreign'}}]), {}), /NATIVE_UPLOAD_RECEIPT_REQUIRED/);
});

test('native binary upload keeps its protocol and bounded session input', () => {
  const url = new URL('http://local/agent/api/session/uploadFileBinary?sessionId=session-local&name=test.pdf');
  const headers = {'content-type':'application/octet-stream','content-length':'128'};
  assert.doesNotThrow(()=>checkUpload(url,headers));
  assert.throws(()=>checkUpload(url,{...headers,'content-type':'application/json'}),error=>error.status===415);
  assert.throws(()=>checkUpload(url,{...headers,'content-length':'21000000'}),error=>error.status===413);
  assert.throws(()=>checkUpload(url,{'content-type':'application/octet-stream'}),error=>error.status===411);
  url.searchParams.set('sessionId','../foreign');
  assert.throws(()=>checkUpload(url,headers),error=>error.status===403);
});

test('native downloads and Office previews require the same session ownership check', async () => {
  const request = (method, scope) => ({type:'client-request',method,payload:{args:{workspaceFileScopeId:scope,path:'work/document.docx',priority:'foreground'}}});
  for (const method of ['workspaceFiles/readBytes','officeToPdf/render']) {
    const envelope = request(method,'session-native');
    await checkRpc(method,envelope,{});
    assert.equal(nativeFileSession(method,envelope),'session-native');
    await assert.rejects(checkRpc(method,request(method,undefined),{}),/SESSION_REQUIRED/);
    await assert.rejects(checkRpc(method,request(method,'../other-owner'),{}),/SESSION_REQUIRED/);
  }
  await checkRpc('officeToPdf/generation',request('officeToPdf/generation'),{});
  assert.equal(nativeFileSession('officeToPdf/generation',request('officeToPdf/generation')),undefined);
  await assert.rejects(checkRpc('officeToPdf/convert',request('officeToPdf/convert','session-native'),{}),/RPC_DENIED/);
});
