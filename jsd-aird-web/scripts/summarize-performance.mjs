import { readFileSync } from 'node:fs';

// Input: [{ scenario: 'projects', cache: 'cold'|'warm', durationMs: 123 }, ...]
const samples = JSON.parse(readFileSync(process.argv[2], 'utf8'));
if (!Array.isArray(samples)) throw new Error('Expected a JSON array');
const groups = new Map();
for (const sample of samples) {
  if (!['cold', 'warm'].includes(sample.cache) || typeof sample.scenario !== 'string' || !Number.isFinite(sample.durationMs) || sample.durationMs < 0) throw new Error('Invalid performance sample');
  const key = `${sample.scenario}/${sample.cache}`;
  const values = groups.get(key) || [];
  values.push(sample.durationMs); groups.set(key, values);
}
for (const [scenario, values] of groups) {
  values.sort((a,b) => a-b);
  const percentile = p => Math.round(values[Math.ceil(p*values.length)-1]);
  console.log(JSON.stringify({ scenario, count: values.length, enoughSamples: values.length >= (scenario.endsWith('/cold') ? 10 : 30), p50Ms: percentile(.5), p75Ms: percentile(.75), p95Ms: percentile(.95), minMs: Math.round(values[0]), maxMs: Math.round(values.at(-1)) }));
}
