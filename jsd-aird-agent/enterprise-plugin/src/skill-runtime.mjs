import { apply as nativeSkills } from '../../../vendor/deepseek-harness/packages/skill/skill-filesystem/lib/index.js';

export const name = 'enterprise-skill-runtime';
export const inject = ['skills'];

// These bundled enterprise skills consist only of SKILL.md. Their complete
// content is loaded by dsh; a Windows host directory is not a sandbox resource.
const inline = skill => skill && ({ ...skill, resourceBase: {
  kind: 'opaque', description: '完整指导已加载，无需读取额外脚本或资源文件。',
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
