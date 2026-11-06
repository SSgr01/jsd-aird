import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

export const revision = 'fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159';
export const engine = 'dsh-v1';
export function configuration(env = process.env) {
  const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
  const secret = env.JSD_AIRD_HARNESS_RUNTIME_SECRET || '';
  if (secret.length < 32) throw new Error('A runtime secret of at least 32 characters is required');
  const origin = new URL(env.JSD_AIRD_PUBLIC_URL || 'http://localhost:5173').origin;
  const hostStartTimeoutMs = Number(env.JSD_AIRD_DSH_START_TIMEOUT_MS || 300000);
  if (!Number.isInteger(hostStartTimeoutMs) || hostStartTimeoutMs < 1000 || hostStartTimeoutMs > 600000)
    throw new Error('Host startup timeout must be between 1000 and 600000 milliseconds');
  const inputModalities = env.JSD_AIRD_DSH_INPUT_MODALITIES?.trim()
    ? env.JSD_AIRD_DSH_INPUT_MODALITIES.split(',').map(value => value.trim()).filter(Boolean) : undefined;
  if (inputModalities && (!inputModalities.includes('text') || inputModalities.some(value => !['text','image'].includes(value))))
    throw new Error('Model input modalities must include text and may include verified image support');
  return {
    repository, secret, origin, hostStartTimeoutMs,
    port: Number(env.JSD_AIRD_DSH_PORT || 8092),
    host: env.JSD_AIRD_DSH_HOST || '127.0.0.1',
    root: resolve(env.JSD_AIRD_DSH_ROOT || resolve(repository, '.runtime/deepseek')),
    executionRoot: resolve(env.JSD_AIRD_DSH_EXECUTION_ROOT || resolve(repository, '.runtime/agent-execution')),
    upstream: resolve(repository, 'vendor/deepseek-harness'),
    plugin: resolve(repository, 'jsd-aird-agent/enterprise-plugin'),
    javaUrl: env.JSD_AIRD_HARNESS_JAVA_URL || 'http://127.0.0.1:8180',
    controlUrl: env.JSD_AIRD_HARNESS_CONTROL_URL || 'http://127.0.0.1:8093',
    node: env.JSD_AIRD_DSH_NODE || process.execPath,
    model: {
      provider: env.JSD_AIRD_DSH_PROVIDER || 'deepseek-official',
      id: env.JSD_AIRD_DSH_MODEL || 'deepseek-flash',
      baseURL: env.JSD_AIRD_DSH_BASE_URL || 'https://api.deepseek.com/anthropic',
      apiKeyEnv: 'JSD_AIRD_DSH_API_KEY',
      ...(inputModalities ? {inputModalities:[...new Set(inputModalities)]} : {}),
      ...(env.JSD_AIRD_DSH_MAX_OUTPUT_TOKENS ? { maxTokens: Number(env.JSD_AIRD_DSH_MAX_OUTPUT_TOKENS) } : {}),
      reasoning: env.JSD_AIRD_DSH_REASONING || undefined,
    },
    maxHosts: Number(env.JSD_AIRD_DSH_MAX_HOSTS || 32),
  };
}

/** Read the pinned upstream blueprint instead of maintaining a second standard preset. */
export function standardPlugins(config) {
  const upstream = config.upstream || resolve(dirname(fileURLToPath(import.meta.url)), '../../../vendor/deepseek-harness');
  const { parse } = createRequire(resolve(upstream, 'packages/settings/settings/package.json'))('yaml');
  const blueprint = parse(readFileSync(resolve(upstream, 'packages/bundle/web-app/presets/standard.patch.yml'), 'utf8'), {
    customTags: [{ tag: 'tag:yaml.org,2002:js', resolve: value => value }],
  });
  // Host plugin installation, delegation and background jobs remain unavailable.
  // Scheduling uses the native Host service and the enterprise execution gates.
  const excluded = new Set(['delegation', 'tool-plugin-manager', 'tool-plugin-market', 'tool-jobs']);
  return blueprint[0].insert[0].config.plugins.filter(row => !excluded.has(row.id)).map(row => {
    if (row.id === 'persona') return { ...row, config: { prefix: '你是由 {{model}} 驱动的企业研发助手。' } };
    // Native instruction discovery must stop at the tenant's execution root.
    if (row.id === 'agent-instructions') return { ...row, config: {
      ...row.config, projectRootMarkers: ['.dsh-workspace-root', '.git'],
    } };
    // The execution world is Linux even when the enterprise gateway runs on Windows.
    if (row.id === 'tool-bash') return { ...row, disabled: false, config: { enableRunInBackground: false, promoteOnTimeout: false } };
    if (row.id === 'tool-pwsh') return { ...row, disabled: true };
    if (row.id === 'skill-filesystem') return { ...row, name: pathToFileURL(resolve(config.plugin, 'src/skill-runtime.mjs')).href, config: {
      includeDefaultRoots: false, bundledSkillDir: resolve(config.plugin, 'skills'), watch: false,
    } };
    return row;
  });
}

/** Native standard behavior, with enterprise authority and execution providers. */
export function enterprisePatch(config) {
  const disabled = [
    'session-log-deepseek', 'session-telemetry-otel', 'desktop-product-telemetry', 'product-analytics',
    'plugin-manager', 'tool-plugin-manager', 'hmr',
    'directory-picker', 'fs-sandbox', 'bash-sandbox', 'pwsh-sandbox', 'terminal-controller', 'subprocess',
    'job-controller', 'account-controller', 'mcp-manager', 'cordis-host-runner',
    'cordis-client-runner', 'cordis-inspect-providers', 'open-in-app',
    'session-log-download', 'subagent', 'agent-team', 'subagent-spawn-in-process',
    'subagent-fork-in-process', 'subagent-model-selection-settings', 'ptc-runtime', 'tool-executor-ptc',
    'llm-deepseek-account', 'web-search-deepseek', 'web-deepseek-api',
    'skill-office',
    'preset-standard', 'preset-minimal', 'preset-ptc', 'preset-cordis',
    'ui-plugin-manager', 'ui-settings-account', 'ui-settings-plugins',
    'ui-settings-shell', 'ui-settings-subagent', 'ui-settings-web-search', 'ui-settings-agent-loop',
    'ui-settings-session-log', 'ui-settings-plugin-inventory', 'ui-cordis', 'ui-agent-team', 'ui-subagent',
    'ui-sidebar-terminal', 'ui-open-in-app',
    'ui-workflow-run',
    'entry-customization', 'ui-brand-official', 'tool-agent-team', 'client-hmr', 'ui-jobs',
  ];
  return [
    ...disabled.map(id => ({ id, disabled: true })),
    { id: 'web', config: { searchProvider: 'enterprise-search', fetchProvider: 'http' } },
    { id: 'agent-default-model', config: { provider: config.model.provider || 'deepseek-official', model: config.model.id,
      ...(config.model.reasoning ? { reasoningEffort: config.model.reasoning } : {}) } },
    { id: 'llm-deepseek', disabled: false, config: {
      apiKeyEnv: config.model.apiKeyEnv || 'JSD_AIRD_DSH_API_KEY', baseURL: config.model.baseURL,
      ...(config.model.reasoning ? { reasoningEffort: config.model.reasoning } : {}),
      ...(config.model.maxTokens ? { maxTokens: config.model.maxTokens } : {}),
      // Gateway model IDs need an explicit catalog. Protocol extensions are
      // enabled only when declared by a verified native provider configuration.
      ...(!['deepseek-flash', 'deepseek-v4-pro'].includes(config.model.id)
        && (config.model.provider || 'deepseek-official') === 'deepseek-official'
        ? { models: [{ id: config.model.id, name: config.model.id,
          ...(config.model.inputModalities ? {inputModalities:config.model.inputModalities} : {}) }] } : {}),
    } },
    // Native Models settings own additional providers, protocols and catalogs.
    { id: 'llm-pi-ai', disabled: false, config: { providers: {} } },
    { id: 'ui-settings-models', disabled: false, config: { credentialOnboarding: false } },
    { id: 'ui-model-selection', disabled: false },
    { id: 'ui-schedule', disabled: false },
    { id: 'session-title-llm', disabled: false, config: {
      targetWords: 5, targetCjkCharacters: 10, maxInputBytes: 4096, maxOutputTokens: 4096,
      timeoutMs: 60000,
    } },
    { id: 'session-controller', config: { nativeOpen: false } },
    { id: 'workspace-controller', config: { documentsDirectory: config.executionRoot } },
    { id: 'ui-chat', config: { transcriptView: 'standard', collapseTiming: 'completion',
      foldedReplies: 'final', performanceUsage: 'detailed', linkOpening: 'sidebar' } },
    { id: 'agent-preset-registry', config: { default: 'enterprise' } },
    { insert: [
      { id: 'preset-enterprise', name: '@deepseek-ai/dsh-agent-preset', config: {
        id: 'enterprise', name: '研发助手', description: '阅读资料、分析问题，联动授权研发数据完成任务', order: 1,
        plugins: standardPlugins(config),
      } },
      { id: 'enterprise-filesystem', name: resolve(config.plugin, 'src/filesystem.mjs') },
      { id: 'enterprise-office-runtime', name: resolve(config.plugin, 'src/office-runtime.mjs') },
      { id: 'enterprise-directory-picker', name: resolve(config.plugin, 'src/directory-picker.mjs') },
      { id: 'ui-directory-picker-browse', name: '@deepseek-ai/dsh-client-ui-directory-picker-browse' },
      { id: 'enterprise', name: resolve(config.plugin, 'src/index.mjs') },
    ] },
  ];
}
