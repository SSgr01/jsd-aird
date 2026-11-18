import { createHash } from 'node:crypto';

/** Publish an authorized query snapshot through the native session filesystem. */
export async function materializeBusinessDataset(ctx, execution, result) {
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
  const { content_base64, ...metadata } = dataset;
  return { ...result, dataset: { ...metadata, path },
    analysis_hint: '使用原生文件读取或脚本读取dataset.path中的JSON。rows保留数值类型；schema给出字段及类型。整理结论或导出Excel时复用此文件，不因更换输出格式再次查询相同数据；需要最新状态、缺少字段或来源版本变化时再查询。' };
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
