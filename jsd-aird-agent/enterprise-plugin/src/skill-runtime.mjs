import { apply as nativeSkills } from '../../../vendor/deepseek-harness/packages/skill/skill-filesystem/lib/index.js';

export const name = 'enterprise-skill-runtime';
export const inject = ['skills'];

// Most enterprise skills are self-contained text. Conversion resources are
// materialized as trusted task copies after source authorization; host paths
// are never advertised as sandbox resources.
const inline = skill => skill && ({ ...skill, resourceBase: {
  kind: 'opaque', description: skill.name === 'jsd-experiment-converter'
    ? '按系统绑定模式准备资源：服务器文件夹任务用 folder_conversion(action=source)，原上传任务用 experiment_conversion(action=source)。参考文件和脚本在当前任务目录 .conversion-tools/jsd-experiment-converter/；文件夹任务先读 references/server-folder.md。'
    : '完整指导已加载，无需读取额外脚本或资源文件。',
} });

export function apply(ctx, config) {
  const skills = { registerProvider(factory) {
    return ctx.skills.registerProvider(control => {
      const provider = factory(control);
      return {
        name: provider.name,
        async list(options) {
          const result = await provider.list(options);
          return Array.isArray(result) ? result.map(inline)
            : { ...result, candidates: result.candidates.map(inline) };
        },
        async get(candidate, options) { return inline(await provider.get(candidate, options)); },
      };
    });
  } };
  nativeSkills(new Proxy(ctx, { get(target, key) {
    return key === 'skills' ? skills : Reflect.get(target, key, target);
  } }), config);
}
