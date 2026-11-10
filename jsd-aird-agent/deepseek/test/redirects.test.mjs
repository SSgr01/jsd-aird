import assert from 'node:assert/strict';
import test from 'node:test';
import { createServer } from 'node:http';
import { browserRedirect } from '../src/redirects.mjs';
import { gateway } from '../src/server.mjs';
import { Hosts } from '../src/hosts.mjs';
import { BackgroundHosts } from '../src/background-hosts.mjs';

const context = {origin:'http://127.0.0.1:5184',prefix:'/agent/',target:'/api/file?path=%2Fworkspaces%2Ffigure.jpg&load=1'};

test('a query-only file version redirect retains the file endpoint and one public prefix', () => {
  assert.equal(browserRedirect('?path=%2Fworkspaces%2Ffigure.jpg&v=123', context),
    '/agent/api/file?path=%2Fworkspaces%2Ffigure.jpg&v=123');
});

test('root-relative and already mounted redirects preserve query and fragment', () => {
  for (const target of ['/api/file?v=123#image','/agent/api/file?v=123#image',context.origin+'/agent/api/file?v=123#image'])
    assert.equal(browserRedirect(target, context), '/agent/api/file?v=123#image');
  assert.equal(browserRedirect('https://example.com/resource',context),'https://example.com/resource');
});

test('upstream login tokens are never forwarded to the browser', () => {
  assert.throws(() => browserRedirect('?token=private',context), /INVALID_UPSTREAM_REDIRECT/);
  assert.throws(() => browserRedirect('https://example.com/?token=private',context), /INVALID_UPSTREAM_REDIRECT/);
});

test('a file image reaches the browser through the authenticated gateway after its version redirect', async t => {
  async function listen(server) {
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    t.after(()=>new Promise(resolve=>server.close(resolve)));
    return `http://127.0.0.1:${server.address().port}`;
  }
  t.mock.method(BackgroundHosts.prototype,'start',()=>{});
  t.mock.method(BackgroundHosts.prototype,'install',async()=>{});
  const paths = [], bytes = Buffer.from('original-image-bytes');
  const native = await listen(createServer((req,res)=>{
    paths.push(req.url);
    if (!new URL(req.url,'http://native.invalid').searchParams.has('v')) {
      res.writeHead(302,{location:'?path=%2Fworkspaces%2Ffigure.jpg&v=123'});res.end();
    } else {res.writeHead(200,{'content-type':'image/jpeg'});res.end(bytes);}
  }));
  const user={organization_id:'organization',actor_id:'user',auth_version:1};
  const javaUrl=await listen(createServer((_req,res)=>{
    res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(user));
  }));
  t.mock.method(Hosts.prototype,'ensure',async()=>({path:'/agent/',port:Number(new URL(native).port),cookie:'native-test',identity:user}));
  const address=await listen(gateway({origin:'http://workbench.invalid',javaUrl,secret:'test-runtime-secret'.repeat(3)}));
  const response=await fetch(address+'/agent/api/file?path=%2Fworkspaces%2Ffigure.jpg&load=1',
    {headers:{cookie:'SESSION=authenticated-test'}});
  assert.equal(response.status,200);
  assert.deepEqual(Buffer.from(await response.arrayBuffer()),bytes);
  assert.deepEqual(paths,['/api/file?path=%2Fworkspaces%2Ffigure.jpg&load=1','/api/file?path=%2Fworkspaces%2Ffigure.jpg&v=123']);
});
