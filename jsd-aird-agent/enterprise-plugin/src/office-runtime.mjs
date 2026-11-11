import { apply as nativeOffice } from '../../../vendor/deepseek-harness/packages/skill/skill-office/lib/index.js';

export const name = 'enterprise-office-runtime';
export const inject = ['skills'];

/** Keep the pinned native skills; adapt only their execution-environment paths. */
export function apply(ctx) {
  const resourceBase = skill => ({ kind: 'directory', path: '/opt/dsh-skills/office/' + skill.name });
  nativeOffice({ skills: { registerProvider(factory) {
    return ctx.skills.registerProvider(options => {
      const provider = factory(options);
      return { ...provider,
        async list(options) {
          return (await provider.list(options)).map(candidate => ({ ...candidate, resourceBase: resourceBase(candidate) }));
        },
        async get(candidate, lookup) {
        const skill = await provider.get(candidate, lookup);
        const suffix = '\n\nLibreOffice Kit is disabled in this deployment.';
        return { ...skill,
          resourceBase: resourceBase(skill),
          content: (!skill.content.endsWith(suffix) ? skill.content : skill.content.slice(0, -suffix.length))
            + '\n\n## Configured execution environment\n\n'
            + 'This deployment uses its preconfigured Linux sandbox. Run Python with /usr/local/bin/python3 '
            + '(python-docx, openpyxl, pandas and pypdf are installed), and Node with /usr/local/bin/node. '
            + 'The load_workspace_dependencies tool is unavailable here; use these configured paths under the native skill fallback. '
            + 'Office skill resources are under /opt/dsh-skills/office/.'
            + '\n\n## Installed LibreOffice Kit\n\nUse these absolute paths for every LibreOffice Kit command. Pass the CLI entry as the first argument to Node.\n\n'
            + JSON.stringify({ libreofficeKit: { node: '/usr/local/bin/node',
              cli: '/opt/dsh-office/node_modules/@deepseek-ai/libreoffice-kit/lib/cli.js' } }, null, 2),
        };
      } };
    });
  } } }, { cli: false });
}
