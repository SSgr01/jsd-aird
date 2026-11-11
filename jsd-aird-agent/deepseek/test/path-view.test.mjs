import test from 'node:test';
import assert from 'node:assert/strict';
import {resolve,join,basename} from 'node:path';
import {browserMetadata,hostDirectory,workspacePath,browserJsonResponse} from '../src/path-view.mjs';
import {Readable} from 'node:stream';

test('workspace, session, picker and rewind metadata share sandbox paths without changing text or file contents', async () => {
  const root=resolve('owner-data'), file=join(root,'配方','备忘.txt');
  const input={items:[{workspaceId:'w',path:root,title:''}],header:{cwd:join(root,'配方')},
    home:resolve('private-home'),files:[{path:file}],crumbs:[{path:root,name:'owner-id'}],
    text:'原文 '+file,content:file,url:'file://'+file,rows:[[0,null,'未测']]};
  const actual=browserMetadata(input,root);
  assert.equal(actual.items[0].path,workspacePath);
  assert.equal(actual.items[0].title,'/workspaces');
  assert.equal(actual.header.cwd,workspacePath+'/配方');
  assert.equal(actual.files[0].path,workspacePath+'/配方/备忘.txt');
  assert.equal(actual.crumbs[0].name,'/workspaces');
  assert.equal(actual.home,workspacePath);
  assert.equal(hostDirectory(root,actual.files[0].path),file);
  assert.equal(actual.content,input.content);assert.equal(actual.text,input.text);assert.equal(actual.url,input.url);
  assert.deepEqual(actual.rows,input.rows);
  assert.deepEqual(browserMetadata({path:root+'-another-owner/file'},root),{path:root+'-another-owner/file'});
  assert.deepEqual(browserMetadata({path:root,title:'乳液工艺'},root),{path:workspacePath,title:'乳液工艺'});
  assert.deepEqual(browserMetadata({workspaceId:'w',path:root,title:basename(root)},root),
    {workspaceId:'w',path:workspacePath,title:'/workspaces'});
  assert.deepEqual(browserMetadata({absolutePath:file},root),{absolutePath:workspacePath+'/配方/备忘.txt'});
  const headers={'content-type':'application/json','content-length':1};
  const body=await browserJsonResponse(Readable.from([Buffer.from(JSON.stringify(input))]),headers,root);
  assert.deepEqual(JSON.parse(body),actual);
  assert.equal(headers['content-length'],Buffer.byteLength(body));
  assert.equal(await browserJsonResponse(null,{'content-type':'application/pdf'},root),undefined);
});
