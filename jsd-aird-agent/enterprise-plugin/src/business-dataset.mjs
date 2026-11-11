import { createHash } from 'node:crypto';

/** Publish an authorized query snapshot through the native session filesystem. */
export async function materializeBusinessDataset(ctx, execution, result) {
  if (result?.business_file) {
    const file = result.business_file;
    const extensions = { 'application/pdf':'pdf', 'image/png':'png', 'image/jpeg':'jpg', 'image/gif':'gif',
      'image/webp':'webp', 'image/tiff':'tiff', 'text/plain':'txt', 'text/csv':'csv',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':'xlsx' };
    const extension = extensions[file.content_type];
    if (!extension || typeof file.name !== 'string' || !file.name || file.name.length > 255 ||
        /[\\/:\x00-\x1f]/.test(file.name)) throw new Error('INVALID_BUSINESS_FILE');
    const bytes = verify(file);
    const path = await publish(ctx, execution, `source-${file.sha256.slice(0,20)}.${extension}`, bytes);
    const {content_base64, ...metadata} = file;
    result = {...result, business_file:{...metadata,path},
      file_analysis_hint:file.content_type.startsWith('image/')
        ? '调用原生read_image(file_path=business_file.path)将这张图片传给当前视觉模型；仅有路径不代表已看图。结合返回的证据ID和原文页码核对。'
        : '使用原生PDF、图片或文件读取工具查看完整原文件。图像观察与原始数值曲线应明确区分。'};
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
    analysis_hint: '使用原生文件读取或脚本读取此JSON。rows保留数值类型；schema给出字段及类型。' };
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
