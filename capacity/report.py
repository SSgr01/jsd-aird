"""Summarize measured stages without converting saturation/rejection into capacity success."""
import json
from pathlib import Path
import sys


def report(root):
    root = Path(root)
    stages = json.loads((root / 'stages.json').read_text(encoding='utf-8-sig'))
    samples = []
    statistics = root / 'containers.ndjson'
    if statistics.exists():
        samples = [json.loads(line.lstrip('\ufeff')) for line in statistics.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    generator = [sample for sample in samples if 'generator' in sample.get('Name', '')]
    saturated = sum(float(s.get('CPUPerc', '0%').rstrip('%')) >= 90 for s in generator)
    generator_suspect = bool(generator) and saturated / len(generator) >= 0.2
    groups = {}
    raw = []
    for stage in stages:
        if stage['name'].endswith('-warmup'):
            continue
        path = root / (stage['name'] + '.json')
        if not path.exists():
            raw.append({**stage, 'accepted': False, 'reason': stage.get('unmeasured', 'missing summary')})
            continue
        summary = json.loads(path.read_text(encoding='utf-8-sig'))
        metrics = summary.get('metrics', {})
        values = lambda name: metrics.get(name, {}).get('values', {})
        attempts = values('business_attempted').get('count', 0)
        complete = values('business_completed').get('count', 0)
        rejected = values('busy_rejected').get('count', 0)
        dropped = values('dropped_iterations').get('count', 0)
        stage_samples = [sample for sample in generator if stage.get('startedAt', '') <= sample.get('sampledAt', '') <= stage.get('finishedAt', '')]
        if not stage.get('startedAt'):
            stage_samples = generator
        stage_saturated = bool(stage_samples) and sum(float(s.get('CPUPerc', '0%').rstrip('%')) >= 90 for s in stage_samples) / len(stage_samples) >= 0.2
        accepted = bool(stage.get('thresholdPass')) and not dropped and not stage_saturated and bool(stage_samples) and attempts > 0
        row = {**stage, 'accepted': accepted, 'attempts': attempts, 'completed': complete,
               'actualCompletedRps': complete / (int(stage['duration'][:-1]) * {'s':1,'m':60,'h':3600}[stage['duration'][-1]]),
               'busyRejected': rejected, 'busyRejectionRate': rejected / attempts if attempts else 0,
               'droppedIterations': dropped, 'generatorSaturationSuspected': stage_saturated,
               'generatorSamples': len(stage_samples), 'read': values('business_read_ms'), 'write': values('business_write_ms')}
        raw.append(row)
        key = stage['profile'] + '/' + stage['model']
        if stage['duration'] == '5m' and not stage['name'].endswith(('-repeat','-tier')):
            group = groups.setdefault(key, {'highestPassing5mLevel':0,'highestStableLevel': 0, 'firstFailedLevel': None})
            if accepted:
                group['highestPassing5mLevel'] = max(group['highestPassing5mLevel'], stage['level'])
            elif group['firstFailedLevel'] is None and not stage_saturated and stage_samples and not dropped:
                group['firstFailedLevel'] = stage['level']
    acceptance_path=root/'acceptance.json'
    evidence=json.loads(acceptance_path.read_text(encoding='utf-8-sig')) if acceptance_path.exists() else {}
    required=['schemaParity','dataConsistency','boundedRecovery','noLeaks','faultCoverage','workspaceSaveExport']
    evidence_complete=all(evidence.get(name) is True for name in required)
    for key,group in groups.items():
        profile,model=key.split('/')
        verified=[row for row in raw if row.get('accepted') and row['profile']==profile and row['model']==model]
        repeat=any(row['name'].endswith('-repeat') and row['level']==group['highestPassing5mLevel'] for row in verified)
        soak=any(row['duration']=='30m' and row['level']==group['highestPassing5mLevel'] for row in verified)
        if evidence_complete and repeat and soak:group['highestStableLevel']=group['highestPassing5mLevel']
        group['recommendedMeasuredLoad'] = group['highestStableLevel'] * 0.7 if group['highestStableLevel'] else None
    ordinary=[profile+'/'+model for profile in ['read','write','mixed'] for model in ['open','closed']]
    accepted_overall=evidence_complete and all(groups.get(key,{}).get('highestStableLevel',0)>0 for key in ordinary)
    output = {'capacityAccepted': accepted_overall, 'generatorSaturationSuspected': generator_suspect,
              'generatorSamples': len(generator), 'groups': groups, 'stages': raw,
              'pending': [name for name in required if evidence.get(name) is not True]+([] if accepted_overall else ['verify repeat/30m soak for ordinary read/write/mixed scenarios']),
              'note': 'Measured load is VUs or RPS as labeled, never a promised production user count.'}
    (root / 'capacity-report.json').write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# 隔离容量实测报告', '', '状态：'+('完整验收通过。' if accepted_overall else '待完整验收。'), '',
             '| 场景／模型 | 5 分钟通过档（暂定） | 最高稳定档 | 首个失败档 | 建议运行档（70%） |', '|---|---:|---:|---:|---:|']
    for key, group in groups.items():
        lines.append(f"| {key} | {group['highestPassing5mLevel']} | {group['highestStableLevel']} | {group['firstFailedLevel']} | {group['recommendedMeasuredLoad']} |")
    lines += ['', '发压器疑似饱和：' + str(generator_suspect), '原始样本与分位数见 capacity-report.json 和各档 ndjson。',
              '未完成工作区／发布负载、数据一致性与资源恢复验收前，不认定整体容量通过。']
    (root / 'CAPACITY_REPORT.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return output


if __name__ == '__main__':
    report(sys.argv[1])
