import { lstat, mkdir, readFile, writeFile, rename, unlink } from 'node:fs/promises';
import { lstatSync, realpathSync } from 'node:fs';
import { relative, resolve, isAbsolute, sep, dirname, posix } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { FileSystem, FsError } from '../../../vendor/deepseek-harness/packages/fs/fs/lib/index.js';
import { LocalFileSystem } from '../../../vendor/deepseek-harness/packages/fs/fs-local/lib/index.js';

export const worldRoot = '/workspaces';
export const sha256 = value => createHash('sha256').update(value).digest('hex');
const denied = () => new FsError('Path is outside the managed execution directory', 'FS_SANDBOX_DENIED');

/** Adds the enterprise boundary and OCI path mapping; all file algorithms remain upstream. */
export default class EnterpriseFileSystem extends FileSystem {
  constructor(ctx) {
    super(ctx);
    this.configuredRoot = resolve(process.env.JSD_DSH_EXECUTION_ROOT);
    if (lstatSync(this.configuredRoot).isSymbolicLink()) throw denied();
    this.root = realpathSync.native(this.configuredRoot);
    this.resourceRoot = process.env.JSD_DSH_OFFICE_SKILLS ? realpathSync.native(process.env.JSD_DSH_OFFICE_SKILLS) : undefined;
    this.attachmentPaths = new Map();
    this.local = new LocalFileSystem(ctx.isolate('fs'), {cwd:this.root,diffBasisMaxBytes:10*1024*1024});
  }
  get sandboxMode() {return 'workspace-write';}
  hostPath(path, cwd = this.root) {
    if (typeof path !== 'string' || !path || /[\x00-\x1f]/.test(path)) throw denied();
    const resource = '/opt/dsh-skills/office';
    const map = value => {
      if (this.resourceRoot && (value === resource || value.startsWith(resource+'/')))
        return resolve(this.resourceRoot, '.'+value.slice(resource.length));
      if (value === worldRoot || value.startsWith(worldRoot+'/'))
        return resolve(this.root, '.'+value.slice(worldRoot.length));
      if (value.startsWith('/') && process.platform === 'win32') throw denied();
      return value;
    };
    cwd = map(cwd);
    path = map(path);
    let target = resolve(cwd,path);
    // Windows may return a canonical long name for a configured 8.3 path.
    // Normalize that alias before applying the same owner boundary.
    const alias = relative(this.configuredRoot,target);
    if (alias !== '..' && !alias.startsWith('..'+sep) && !isAbsolute(alias)) target = resolve(this.root,alias);
    const base = this.resourceRoot && this.isResource(target) ? this.resourceRoot : this.root;
    const part = relative(base,target);
    if (part === '..' || part.startsWith('..'+sep) || isAbsolute(part) || part.split(sep).some(p=>/[:\x00-\x1f]/.test(p)||/[. ]$/.test(p))) throw denied();
    return target;
  }
  async checked(path, cwd) {
    const target = this.hostPath(path,cwd), resource = this.resourceRoot && this.isResource(target);
    const base = resource ? this.resourceRoot : this.root, parts = relative(base,target).split(sep).filter(Boolean);
    let current = base;
    for (const name of ['',...parts]) {
      current = name ? resolve(current,name) : current;
      try {if ((await lstat(current)).isSymbolicLink()) throw denied();}
      catch(error) {if(error.code !== 'ENOENT') throw error;}
    }
    return target;
  }
  async target(value) {return this.local.resolve(await this.checked(String(value.targetKey)));}
  async resolve(path, opts = {}) {
    const target = await this.local.resolve(await this.checked(path,opts.cwd), {signal:opts.signal});
    return {...target,displayPath:this.processPath(target)};
  }
  isResource(path) {const part=relative(this.resourceRoot,path);return part===''||part!=='..'&&!part.startsWith('..'+sep)&&!isAbsolute(part);}
  processPath(target) {
    const path=this.hostPath(String(target.targetKey));
    if(this.resourceRoot && this.isResource(path))return posix.join('/opt/dsh-skills/office',relative(this.resourceRoot,path).split(sep).join('/'));
    return posix.join(worldRoot,relative(this.root,path).split(sep).join('/'));
  }
  processPathFromHostPath(path) {
    if (this.attachmentPaths.has(path)) return this.attachmentPaths.get(path);
    try {return this.processPath({targetKey:this.hostPath(path)});} catch {return undefined;}
  }
  /** Copy exact native attachment bytes into the same sandbox-visible directory. */
  async exposeAttachment(ref, bytes, cwd, originalPath) {
    if ('sha256:' + sha256(bytes) !== ref.attachmentId || bytes.length !== ref.bytes) throw new Error('NATIVE_ATTACHMENT_MISMATCH');
    const target = await this.resolve(posix.join('.dsh-attachments',sha256(bytes),ref.name),{cwd});
    await this.publish(String(target.targetKey),bytes);
    this.attachmentPaths.set(originalPath,this.processPath(target));
  }
  fileUrl(target) {return 'file://'+this.processPath(target).split('/').map(encodeURIComponent).join('/');}
  contains(parent,child) {const part=relative(this.hostPath(String(parent.targetKey)),this.hostPath(String(child.targetKey)));return part===''||part!=='..'&&!part.startsWith('..'+sep)&&!isAbsolute(part);}
  async stat(target,signal) {return this.local.stat(await this.target(target),signal);}
  async lstat(path,opts,signal) {return this.local.lstat(await this.checked(path,opts?.cwd),undefined,signal);}
  async readText(target,signal) {return this.local.readText(await this.target(target),signal);}
  async streamText(target,signal) {return this.local.streamText(await this.target(target),signal);}
  async readBytes(target,signal,maxBytes) {return this.local.readBytes(await this.target(target),signal,maxBytes);}
  async readByteRange(target,range,signal) {return this.local.readByteRange(await this.target(target),range,signal);}
  async listDir(target,signal) {
    const entries=await this.local.listDir(await this.target(target),signal), result=[];
    for(const entry of entries) {try {await this.target(entry.target);result.push({...entry,target:{...entry.target,displayPath:this.processPath(entry.target)}});} catch(error) {if(error.code!=='FS_SANDBOX_DENIED') throw error;}}
    return result;
  }
  async watch(target,changed,signal) {return this.local.watch(await this.target(target),changed,signal);}
  mutation(policy,...targets) {if(policy?.mode==='read-only'||policy?.mode==='danger-full-access'
    ||this.resourceRoot&&targets.some(target=>this.isResource(this.hostPath(String(target.targetKey)))))throw denied();}
  async writeText(target,text,intent,signal,policy) {this.mutation(policy,target);return this.local.writeText(await this.target(target),text,intent,signal);}
  async editText(target,edit,intent,signal,policy) {this.mutation(policy,target);return this.local.editText(await this.target(target),edit,intent,signal);}
  async patchText(target,hunks,intent,signal,policy) {this.mutation(policy,target);return this.local.patchText(await this.target(target),hunks,intent,signal);}
  async deleteFile(target,intent,signal,policy) {this.mutation(policy,target);return this.local.deleteFile(await this.target(target),intent,signal);}
  async moveFile(source,destination,options,signal,policy) {this.mutation(policy,source,destination);return this.local.moveFile(await this.target(source),await this.target(destination),options,signal);}
  // Binary transfer belongs to the sandbox bridge, not a second model-facing file tool.
  async publish(path,bytes) {
    const destination=await this.checked(path);this.mutation(undefined,{targetKey:destination});await mkdir(dirname(destination),{recursive:true});await this.checked(destination);
    const temporary=destination+'.'+randomUUID()+'.tmp';
    try {await writeFile(temporary,bytes,{flag:'wx'});await this.checked(destination);await rename(temporary,destination);}
    finally {await unlink(temporary).catch(error=>{if(error.code!=='ENOENT')throw error;});}
  }
  async snapshot(cwd,signal,{excludeProcessOutput=false}={}) {
    const root=await this.resolve(cwd), files=[];let total=0;
    const visit=async (target,path='')=>{
      for(const entry of await this.listDir(target,signal)) {
        if(excludeProcessOutput && entry.name==='.dsh-process-output')continue;
        const name=path?path+'/'+entry.name:entry.name;
        if(entry.type==='directory') await visit(entry.target,name);
        else if(entry.type==='file') {
          if(files.length>=50)throw new Error('SANDBOX_FILE_COUNT_EXCEEDED');
          const content=await this.readBytes(entry.target,signal,10*1024*1024);total+=content.length;
          if(total>10*1024*1024)throw new Error('SANDBOX_INPUT_BYTES_EXCEEDED');
          files.push({path:name,base64:Buffer.from(content).toString('base64'),sha256:sha256(content)});
        }
      }
    };
    await visit(root);return files.sort((a,b)=>a.path.localeCompare(b.path));
  }
}
