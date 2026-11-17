"""Index raw material names and propose source-summary links without assigning IDs."""
from __future__ import annotations
import argparse
from collections import defaultdict
import csv
import json
from pathlib import Path
import re
import tempfile
import unicodedata

from history_sources import contained
from workflow import digest,read_json,write_json


def comparison_key(name):
    # Comparison only: never strip concentration, solvent, composition or suffix.
    return ' '.join(unicodedata.normalize('NFKC',name).split())


def material_index(documents):
    names={};format_groups=defaultdict(set);code_groups=defaultdict(set)
    for document in documents:
        source=document['source']
        for record in document['experiments']:
            for item in record['formulaItems']:
                raw=str(item['materialName'])
                if not raw.strip():raise ValueError('物料原名不能为空')
                if raw not in names:
                    names[raw]={'rawName':raw,'comparisonKey':comparison_key(raw),'proposedMaterialId':None,
                        'matchStatus':'NEEDS_CATALOG_REVIEW','occurrences':[],'approval':'NOT_GRANTED'}
                names[raw]['occurrences'].append({'sourcePath':source['source'],'sourceHash':source['source_sha256'],
                    'recordKey':record['recordKey'],'recordRole':record.get('recordRole','EXPERIMENT'),
                    'sourceGroupKey':item['sourceGroupKey'],'stageKey':item.get('stageKey',''),
                    'sequence':item.get('sequence'),'rawAmount':item['rawValue'],'unit':item['unit'],
                    'sourceRefs':item['sourceRefs']})
    for raw,item in names.items():
        key=item['comparisonKey'];format_groups[key].add(raw)
        # Hyphen/case candidates are restricted to code-shaped names. A mixed
        # solution such as X/10%(S-6) stays distinct from X and X/20%(S-6).
        if re.fullmatch(r'[A-Za-z0-9 -]+',key) and re.search(r'\d',key):
            code_groups[re.sub(r'[- ]','',key).upper()].add(raw)
        item['fileCount']=len({x['sourcePath'] for x in item['occurrences']})
        item['occurrenceCount']=len(item['occurrences'])
        item['primaryOccurrences']=sum(x['recordRole']=='EXPERIMENT' for x in item['occurrences'])
        item['summaryOccurrences']=sum(x['recordRole']=='SUMMARY' for x in item['occurrences'])
    aliases=[]
    for kind,groups in [('FORMAT_ONLY',format_groups),('CODE_PUNCTUATION_OR_CASE',code_groups)]:
        for key,values in sorted(groups.items()):
            if len(values)>1:
                aliases.append({'kind':kind,'comparisonKey':key,'rawNames':sorted(values),
                    'status':'PROPOSED_ONLY','merged':False,'approval':'NOT_GRANTED'})
    return {'materials':[names[n] for n in sorted(names)],'aliasCandidates':aliases,
        'summary':{'rawNames':len(names),'occurrences':sum(x['occurrenceCount'] for x in names.values()),
            'aliasCandidates':len(aliases),'assignedMaterialIds':0},'approval':'NOT_GRANTED'}


def summary_links(documents):
    links=[]
    for document in documents:
        source=document['source'];primary=defaultdict(list)
        for record in document['experiments']:
            if record.get('recordRole','EXPERIMENT')!='EXPERIMENT':continue
            for group in record['sourceGroups']:
                identity=str(group.get('sourceIdentity','')).strip()
                if not identity:continue
                field=group.get('properties',{}).get('date',record.get('metadata',{}).get('date'))
                primary[identity].append({'recordKey':record['recordKey'],'sourceGroupKey':group['sourceGroupKey'],
                    'sourceIdentity':group['sourceIdentity'],'dateEvidence':field,'sourceRefs':group['sourceRefs']})
        for record in document['experiments']:
            if record.get('recordRole')!='SUMMARY':continue
            for group in record['sourceGroups']:
                identity=str(group.get('sourceIdentity','')).strip();candidates=primary.get(identity,[]) if identity else []
                links.append({'sourcePath':source['source'],'sourceHash':source['source_sha256'],
                    'summaryRecordKey':record['recordKey'],'summaryGroupKey':group['sourceGroupKey'],
                    'sourceIdentity':group['sourceIdentity'],'sourceRefs':group['sourceRefs'],'candidates':candidates,
                    'status':'NO_EXACT_LABEL' if not candidates else 'EXACT_LABEL_CANDIDATE' if len(candidates)==1 else 'AMBIGUOUS_LABEL',
                    'independentExperiment':False,'dateInherited':False,'merged':False,'approval':'NOT_GRANTED'})
    return links


def export_index(root,inputs,output):
    root=Path(root).resolve();destination=contained(root,output)
    if destination.exists():raise ValueError('物料原名整理结果已存在，不覆盖待核对内容')
    documents=[]
    for relative in inputs:
        path=contained(root,relative);receipt=read_json(path.parent/'verification.json')
        if receipt.get('artifacts',{}).get(path.name)!=digest(path):raise ValueError('历史数据指纹不符')
        document=read_json(path);source=document['source']
        if digest(contained(root,source['source']))!=source['source_sha256']:raise ValueError('工作副本已变化')
        original=source.get('original')
        if original and digest(contained(root,original['path']))!=original['sha256']:raise ValueError('原件已变化')
        documents.append(document)
    result=material_index(documents);links=summary_links(documents)
    result['summary']['summaryGroups']=len(links)
    result['scope']='仅原名索引、格式候选及汇总关联建议；不合并物料、不继承日期、不赋系统ID、不修改实验事实'
    destination.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.materials-',dir=destination.parent) as temporary:
        stage=Path(temporary)
        write_json(stage/'materials.json',result);write_json(stage/'summary-links.json',{'items':links,'approval':'NOT_GRANTED'})
        with (stage/'materials.csv').open('w',encoding='utf-8-sig',newline='') as h:
            writer=csv.writer(h);writer.writerow(['物料原名','比对键（非标准名）','涉及文件数','原文出现次数','实验中次数','汇总中次数','系统物料ID（待匹配）','状态'])
            for x in result['materials']:writer.writerow([x['rawName'],x['comparisonKey'],x['fileCount'],x['occurrenceCount'],x['primaryOccurrences'],x['summaryOccurrences'],'','待真实主数据核对'])
        stage.rename(destination)
    return result['summary']


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',required=True)
    p.add_argument('--input',action='append',required=True);p.add_argument('--output',required=True);a=p.parse_args()
    try:print(json.dumps(export_index(a.root,a.input,a.output),ensure_ascii=False));return 0
    except Exception as error:print(json.dumps({'status':'BLOCKED','reason':str(error)},ensure_ascii=False));return 2


if __name__=='__main__':raise SystemExit(main())
