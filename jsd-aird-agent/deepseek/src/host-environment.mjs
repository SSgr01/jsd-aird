/** Forward only runtime essentials and explicitly routed model credentials. */
export function hostEnvironment(env = process.env) {
  return Object.fromEntries([
    'PATH', 'Path', 'SystemRoot', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT',
    'TEMP', 'TMP', 'USERPROFILE', 'HOME', 'APPDATA', 'LOCALAPPDATA', 'LANG', 'LC_ALL',
    'JSD_AIRD_DSH_API_KEY', 'JSD_AIRD_DSH_LOCAL_MODEL_API_KEY',
  ].filter(key => env[key] !== undefined).map(key => [key, env[key]]));
}
