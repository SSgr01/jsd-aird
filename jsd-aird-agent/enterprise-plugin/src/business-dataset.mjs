import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const recordExportScript = readFile(new URL('./business-record-export.py', import.meta.url));

/** Publish an authorized query snapshot through the native session filesystem. */
export async function materializeBusinessDataset(ctx, execution, result) {
  // Keep authorized record values available to scripts without model retyping.
  // Display text can translate null into words; it is not an export dataset.
  if (!result?.dataset && !result?.error) {
    const records = [];
    for (const candidate of result?.candidates || []) {
      if (candidate.source?.kind !== 'BUSINESS_RECORD' || typeof candidate.text !== 'string') continue;
      if (createHash('sha256').update(candidate.text).digest('hex') !== candidate.source.content_sha256)
        throw new Error('BUSINESS_RECORD_HASH_MISMATCH');
      let content;
      try { content = JSON.parse(candidate.text); } catch { continue; }
      if (!content || typeof content !== 'object') continue;
      records.push({ref:candidate.ref, evidence_id:candidate.evidence_id, source:candidate.source,
        url:result.citation_links?.[candidate.evidence_id]?.url, content});
    }
    if (records.length) {
      const rows = [];
      for (const record of records) {
        const content=record.content;
        const sections=content.section==='record' ? content.sections : {[content.section]:[content]};
        for (const [section,pages] of Object.entries(sections || {})) for (const page of Array.isArray(pages)?pages:[]) {
          for (const row of Array.isArray(page.records)?page.records:[]) {
            if (!Array.isArray(row.values)) continue;
            const {values,...fields}=row;
            for (const value of values) rows.push({experiment_id:record.source.resource_id,
              version_id:record.source.version_id, evidence_id:record.evidence_id,
              registration:content.current_registration, section,...fields,...value});
          }
        }
      }
      const bytes=Buffer.from(JSON.stringify({schema_version:1,records,rows}));
      if (bytes.length <= 8*1024*1024) {
        const hash=createHash('sha256').update(bytes).digest('hex');
        result={...result,dataset:{name:`business-${hash.slice(0,20)}.json`,bytes:bytes.length,
          sha256:hash,content_base64:bytes.toString('base64'),format:'business-records'}};
      }
    }
  }
  if (result?.business_file) {
    const file = result.business_file;
    const extensions = { 'application/pdf':'pdf', 'image/png':'png', 'image/jpeg':'jpg', 'image/gif':'gif',
      'image/webp':'webp', 'image/tiff':'tiff', 'text/plain':'txt', 'text/csv':'csv',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':'xlsx',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document':'docx',
      'application/msword':'doc', 'application/vnd.ms-excel':'xls', 'application/rtf':'rtf', 'text/rtf':'rtf', 'text/tab-separated-values':'tsv' };
    const extension = extensions[file.content_type];
    if (!extension || typeof file.name !== 'string' || !file.name || file.name.length > 255 ||
        /[\\/:\x00-\x1f]/.test(file.name)) throw new Error('BUSINESS_FILE_FORMAT_UNSUPPORTED: 原文件名称或格式暂不支持原生传输，请通过业务页面阅读');
    const bytes = verify(file);
    const path = await publish(ctx, execution, `source-${file.sha256.slice(0,20)}.${extension}`, bytes);
    const {content_base64, text_projection, ...metadata} = file;
    let readingPath;
    if(text_projection){
      if(text_projection.source_sha256!==file.sha256)throw new Error('BUSINESS_PROJECTION_SOURCE_MISMATCH');
      const projectionBytes=verify(text_projection);
      readingPath=await publish(ctx,execution,`source-${file.sha256.slice(0,20)}-${text_projection.sha256.slice(0,12)}.txt`,projectionBytes);
    }
    result = {...result, business_file:{...metadata,path,...(readingPath?{reading_path:readingPath,text_projection:{source_sha256:file.sha256,sha256:text_projection.sha256,bytes:text_projection.bytes,parser:text_projection.parser}}:{})},
      file_analysis_hint:file.content_type.startsWith('image/')
        ? '调用原生read_image(file_path=business_file.path)将这张图片传给当前视觉模型；仅有路径不代表已看图。结合返回的证据ID和原文页码核对。'
        : readingPath?'调用read(file_path=business_file.reading_path)阅读原上传文件的文本投影，按返回行数继续读取；下载不代表已阅读。内嵌图片、扫描内容和版式不在文本投影中，原件保留在business_file.path。'
          : file.reading_notice||'使用原生文件能力读取原文件；二进制Office或PDF不能用文本read直接阅读，可先读取页面已保存正文。'};
  }
  const dataset = result?.dataset;
  if (!dataset) return result;
  if (!/^business-[a-f0-9]{20}\.json$/.test(dataset.name || '') ||
      !Number.isSafeInteger(dataset.bytes) || dataset.bytes < 0 || dataset.bytes > 8 * 1024 * 1024 ||
      typeof dataset.content_base64 !== 'string' || dataset.content_base64.length > 12 * 1024 * 1024) {
    throw new Error('INVALID_BUSINESS_DATASET');
  }
  const bytes = verify(dataset);
  if (dataset.name !== `business-${dataset.sha256.slice(0, 20)}.json`) {
    throw new Error('BUSINESS_DATASET_HASH_MISMATCH');
  }
  const path = await publish(ctx, execution, dataset.name, bytes);
  const exportScript = dataset.format==='business-records'
    ? await publish(ctx, execution, 'export-records.py', await recordExportScript) : undefined;
  const { content_base64, ...metadata } = dataset;
  return { ...result, dataset: { ...metadata, path, ...(exportScript?{export_script:exportScript}:{}) },
    analysis_hint: dataset.format==='business-records'
      ? '系统记录导出直接运行 /usr/local/bin/python3 .business-data/export-records.py --input 本轮所有相关dataset.path --output 整理结果.xlsx，然后present。现成脚本保留登记字段、状态、版本、分区原值与可点出处，并重新打开逐值核对；不要手写转换脚本，不调用历史文件转换skill。records保留固定版本完整原值；rows按分区、字段、样品展开。null必须写成空白单元格，状态及说明另列。不因转Excel重新搜索、重读或重写源数据。'
      : '使用原生文件读取或脚本读取dataset.path中的JSON。rows保留数值类型；schema给出字段及类型。整理结论或导出Excel时复用此文件，不因更换输出格式再次查询相同数据；需要最新状态、缺少字段或来源版本变化时再查询。' };
}

function verify(file) {
  if (!Number.isSafeInteger(file.bytes) || file.bytes < 0 || file.bytes > 8 * 1024 * 1024 ||
      typeof file.content_base64 !== 'string' || file.content_base64.length > 12 * 1024 * 1024 ||
      !/^[a-f0-9]{64}$/.test(file.sha256 || '')) throw new Error('INVALID_BUSINESS_FILE');
  const bytes = Buffer.from(file.content_base64, 'base64');
  if (bytes.length !== file.bytes || createHash('sha256').update(bytes).digest('hex') !== file.sha256)
    throw new Error('BUSINESS_DATASET_HASH_MISMATCH');
  return bytes;
}

async function publish(ctx, execution, name, bytes) {
  execution.signal?.throwIfAborted();
  const cwd = await ctx.workingDirectory.ensure(execution.agent, execution.signal);
  const path = `.business-data/${name}`;
  const target = await ctx.fs.resolve(path, {cwd,signal:execution.signal});
  execution.signal?.throwIfAborted();
  await ctx.fs.publish(String(target.targetKey), bytes);
  return path;
}
