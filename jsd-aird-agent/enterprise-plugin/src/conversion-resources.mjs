import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const root = new URL('../skills/jsd-experiment-converter/', import.meta.url);
// Only reviewed runtime resources enter a task. No desktop paths, caches or sample data.
export const conversionFiles = [
  'scripts/workflow.py','scripts/execute_plan.py','scripts/template_ops.py','scripts/workbook_audit.py','scripts/is_template_copy.py',
  'scripts/history_sources.py','scripts/history_data.py','scripts/history_training.py','scripts/history_template.py','scripts/history_review.py','scripts/history_materials.py',
  ...['workspace','execution','decisions','template-layouts','qa','legacy-formats','server-folder','history-data'].map(name=>'references/'+name+'.md'),
];

/** Materialize trusted working copies through the existing native file boundary. */
export async function prepareConversionResources(ctx, execution, result) {
  if (result?.conversion_resources !== true) return result;
  const task=result.task, file=result.business_file;
  if(!task || !['test','synthesis'].includes(task.kind) || !/^[a-f0-9-]{36}$/.test(task.id)
    || typeof file?.path!=='string')throw new Error('INVALID_CONVERSION_TASK');
  const cwd=await ctx.workingDirectory.ensure(execution.agent,execution.signal);
  async function publish(path, bytes) {
    execution.signal?.throwIfAborted();
    const target=await ctx.fs.resolve(path,{cwd,signal:execution.signal});
    await ctx.fs.publish(String(target.targetKey),bytes);
    return path;
  }
  for(const path of conversionFiles)await publish('.conversion-tools/jsd-experiment-converter/'+path,await readFile(new URL(path,root)));
  for(const kind of ['test','synthesis']) await publish('标准模板/'+kind+'.xlsx',await readFile(new URL('templates/'+kind+'.xlsx',root)));
  const source=await ctx.fs.resolve(file.path,{cwd,signal:execution.signal});
  const bytes=await ctx.fs.readBytes(source,execution.signal,8*1024*1024);
  if(createHash('sha256').update(bytes).digest('hex')!==task.sourceSha256)throw new Error('CONVERSION_SOURCE_MISMATCH');
  const sourcePath=await publish('原始实验/'+(task.kind==='test'?'测试实验':'合成实验')+'/'+file.name,bytes);
  return {...result,conversion_workspace:'.',source_path:sourcePath,
    skill_resources:'.conversion-tools/jsd-experiment-converter',
    conversion_hint:'已准备原件与空白模板。读取 jsd-experiment-converter skill，按当前任务目录运行 /usr/local/bin/python3 .conversion-tools/jsd-experiment-converter/scripts/workflow.py inspect --root .；脚本和参考文件都在 skill_resources。保持原件与模板只读。完成后将结果 XLSX、映射计划 JSON、verification.json 分别用 artifact_save 登记，取返回的真实 id（可再用 present 展示结果） 调用 experiment_conversion(action=stage)。机器校验状态不会代替人工核对。单个文件一个专家会话，不处理其他转换任务。'};
}
