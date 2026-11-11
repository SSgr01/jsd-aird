const presets = Object.freeze({ agent: 'enterprise' });

export function presetForProfile(profile = 'agent') {
  if (!Object.hasOwn(presets, profile)) throw Object.assign(new Error('INVALID_PROFILE'), { status: 422 });
  return presets[profile];
}

export function profileForPreset(preset) {
  const entry = Object.entries(presets).find(([, value]) => value === preset);
  if (!entry) throw Object.assign(new Error('ENTERPRISE_PRESET_REQUIRED'), { status: 403 });
  return entry[0];
}
