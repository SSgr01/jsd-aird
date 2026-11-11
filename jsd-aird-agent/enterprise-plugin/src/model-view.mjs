import {shortSourceTitle, sourceLinkLabel} from './source-links.mjs';
/** Model/UI projections of business results. Authority stays in the business service. */
const resourceNames = {
  PROJECT:'项目', STAGE:'项目阶段', TASK:'项目任务', REQUIREMENT:'项目需求', MATERIAL:'项目资料',
  EXPERIMENT:'实验', STRUCTURED_DATA:'结构化研发数据', DATA_IMPORT:'导入文件', STRUCTURED_RECORD:'原始数据行',
  RESEARCH_TEST:'研发测试报告', CUSTOMER:'客户', CUSTOMER_CONTACT:'客户联系人', CUSTOMER_COMMUNICATION:'客户跟进',
  CUSTOMER_REQUIREMENT:'客户需求', INVENTORY_BALANCE:'库存余额', INVENTORY_TRANSACTION:'库存流水',
  SAMPLE_DISPATCH:'发样单', SHIPMENT:'出货单', PRODUCTION_ORDER:'生产单', PRODUCTION_UPLOAD:'上传生产单',
  QUALITY_STANDARD:'品管标准', QUALITY_RECORD:'品管检验记录', QUALITY_COA:'COA 证书', QUALITY_DEFECT:'不良记录',
  QUALITY_MANUAL:'产品说明书', QUALITY_MSDS:'安全说明书', QUALITY_LABEL:'产品标签',
  SPECTRUM:'谱图', SPECTRUM_ANALYSIS:'谱图分析',
};
function failureMessage(value) {
  if (value.error_code === 'AUTH_REQUIRED') return '登录状态已失效，请重新登录。';
  if (String(value.error).startsWith('RETRIEVAL_TIMEOUT'))
    return '资料检索超时，覆盖不完整。';
  if (value.access_denied) return '当前账号无权读取所需业务记录。';
  if (value.requires_reconciliation || String(value.error).startsWith('TOOL_RESULT_UNKNOWN'))
    return '业务操作结果尚未确认，需要先核对原操作回执，不能重复提交。';
  const message = String(value.error);
  if (/^(?:FILE_REQUIRED|NOT_FOUND|AMBIGUOUS)/.test(message)) return '尚未找到可读取的明确记录，请确认资料或重新查找。';
  if (/^VERSION_CONFLICT|^RETRIEVAL_SNAPSHOT_CHANGED/.test(message)) return '记录版本已变化，请重新查找并读取固定版本。';
  if (/^BUSINESS_VALIDATION:/.test(message)) return message.replace(/^BUSINESS_VALIDATION:\s*/, '业务参数需要调整：');
  if (/[\u4e00-\u9fff]/.test(message)) return message;
  return '业务请求失败。';
}
function sourceLabel(source = {}) {
  source ||= {};
  const anchor = source.anchor || {}, original = anchor.original || {};
  return Object.fromEntries(Object.entries({ title: source.title,
    ...Object.fromEntries(['document_id','publication_id','version_id','resource_type','resource_id','project_id']
      .filter(key => source[key] !== undefined && source[key] !== null).map(key => [key,source[key]])),
    page: anchor.page_no || anchor.page || original.page_no || original.page,
    section: anchor.section_title || anchor.section, sheet: anchor.sheetName, cell: anchor.cellAddress,
  }).filter(([,value]) => value !== undefined && value !== null));
}
export function modelResult(value) {
  return compactDocumentSources(projectResult(value));
}
export function renderModelResult(value, maxCharacters) {
  const text=JSON.stringify(modelResult(value));
  if (maxCharacters !== undefined && Array.from(text).length > maxCharacters)
    throw new Error('工具结果超出本次读取预算，请缩小分页或读取范围。');
  return [{type:'text',text}];
}
function documentIdentity(value) {
  return value.document_id && !value.resource_type && (value.publication_id || value.version_id)
    ? JSON.stringify([value.document_id,value.publication_id,value.version_id ?? null]) : undefined;
}
/** References belong to this result, including on replay and after compaction. */
function compactDocumentSources(value) {
  if (Array.isArray(value)) return value.map(compactDocumentSources);
  if (!value || typeof value !== 'object') return value;
  const sources=new Map(), shared=new Set(['title','document_id','publication_id','version_id']);
  function visit(item) {
    if (Array.isArray(item)) return item.map(visit);
    if (!item || typeof item !== 'object') return item;
    const key=documentIdentity(item);
    if (key) {
      if (!sources.has(key)) sources.set(key,{ref:'d'+(sources.size+1),
        ...Object.fromEntries(Object.entries(item).filter(([name])=>shared.has(name)))});
      return {document_ref:sources.get(key).ref,
        ...Object.fromEntries(Object.entries(item).filter(([name])=>!shared.has(name)).map(([name,field])=>[name,visit(field)]))};
    }
    const output=Object.fromEntries(Object.entries(item).filter(([name])=>name!=='document_metadata').map(([name,field])=>[name,visit(field)]));
    for (const metadata of item.document_metadata || []) {
      const key=documentIdentity(metadata);
      if (key) {
        if (!sources.has(key)) sources.set(key,{ref:'d'+(sources.size+1)});
        Object.assign(sources.get(key),Object.fromEntries(Object.entries(metadata).filter(([name])=>name!=='ref')));
      } else (output.document_metadata ||= []).push(visit(metadata));
    }
    return output;
  }
  const output=visit(value);
  if (sources.size) output.document_sources=[...sources.values()];
  return output;
}
function projectResult(value) {
  if (Array.isArray(value)) return value.map(projectResult);
  if (!value || typeof value !== 'object') return value;
  const output = {};
  for (const [key,item] of Object.entries(value)) {
    if (['workspace_path','content_sha256','content_base64','retrieval_trace','source_refs','citation_links'].includes(key)) continue;
    if (key === 'error' && item) output[key] = failureMessage(value);
    else if (key === 'resource_types' && Array.isArray(item)) {
      output[key] = item.filter(resource => resource.available === true).map(resource => ({
        ...resource, name:resourceNames[resource.type] || resource.name || '业务记录',
      }));
      const closed = item.filter(resource => resource.available === false);
      if (closed.length) output.unavailable_resources = closed.map(resource => resourceNames[resource.type] || resource.name || '业务记录');
      output.reading_notice = '能力目录：请按 type 及 required_filters 查询。';
    } else if (key === 'hits' && Array.isArray(item)) {
      output[key] = item.map(hit => {
        if (!hit.source || (!hit.source_text && !hit.quote && !hit.context_quotes && !hit.already_retrieved && !hit.requires_read)) return projectResult(hit);
        const quotes = [...(hit.context_quotes || []), ...(hit.quote ? [hit.quote] : [])]
          .sort((a,b) => (a.start || 0) - (b.start || 0));
        return {evidence_id:hit.evidence_id, ...sourceLabel(hit.source),
          ...Object.fromEntries(['node_offset','quote_offset','quote_count','table_context','requires_read','already_retrieved']
            .filter(name=>hit[name]!==undefined).map(name=>[name,hit[name]])),
          ...(hit.source_text || quotes.length ? {text:hit.source_text ?? quotes.map(quote=>quote.text).join('\n')} : {}),
          ...(hit.quote_id || hit.quote?.quote_id ? {quote_id:hit.quote_id || hit.quote.quote_id} : {})};
      });
    } else if (['candidates','context_candidates'].includes(key) && Array.isArray(item) && item.every(candidate => candidate.evidence_id || candidate.source)) {
      output[key] = item.map(candidate => ({ evidence_id: candidate.evidence_id,
        ...sourceLabel(candidate.source), offset: candidate.node_offset, text: candidate.model_text ?? candidate.text,
        ...(candidate.ref ? {ref: candidate.ref} : {}), ...(candidate.relation ? {relation: candidate.relation} : {}),
        ...(!documentIdentity(sourceLabel(candidate.source)) && value.citation_links?.[candidate.evidence_id]?.url
          ? {url:value.citation_links[candidate.evidence_id].url} : {}),
        ...(candidate.quote_id ? {quote_id:candidate.quote_id} : {}),
        ...Object.fromEntries(['already_retrieved','quote_count','table_context'].filter(key=>candidate[key]!==undefined).map(key=>[key,candidate[key]])),
        ...Object.fromEntries(['record_key','field','value','unit','value_type','condition','source_status']
          .filter(key=>candidate[key]!==undefined).map(key=>[key,candidate[key]])),
      }));
    } else if (key === 'entries' && Array.isArray(item) && value.trace?.total_nodes !== undefined) {
      output[key] = item.map(entry => ({
        ...Object.fromEntries(['node_offset','end_offset_exclusive','kind','title']
          .filter(name => entry[name] !== undefined).map(name => [name,entry[name]])),
        ...Object.fromEntries(Object.entries(sourceLabel({anchor:entry.anchor}))
          .filter(([name]) => ['page','section'].includes(name))),
      }));
    } else if (key === 'source') output[key] = sourceLabel(item);
    else if (key === 'trace') output[key] = Object.fromEntries(['mode','total_nodes','offset','read_at','status','next_cursor','total','section',
      'ranking_scope','fulltext_coverage','coverage_note','new_evidence_count','new_source_versions','cache_hit',
      'reused_evidence_count','omitted_characters']
      .filter(name => item?.[name] !== undefined).map(name => [name,item[name]]));
    else output[key] = projectResult(item);
  }
  return output;
}
export function presentation(name, value) {
  const links = (value.candidates || []).flatMap(candidate => {
    const link = value.citation_links?.[candidate.evidence_id];
    return link ? [{label: candidate.source?.kind === 'BUSINESS_RECORD' || !/\/content(?:[?#]|$)/.test(link.url)
      ? (candidate.source?.title ? `${candidate.source.title} · ${link.label}` : link.label)
      : sourceLinkLabel(candidate.source?.anchor?.section_title || candidate.source?.anchor?.section || shortSourceTitle(candidate.source?.title), new URL(link.url,'http://localhost')), url:link.url}] : [];
  });
  const sources = [...new Map(links.map(link => [link.url, link])).values()].slice(0,8);
  return { enterprise: true, name, ...(Array.isArray(value.candidates) ? {count:value.candidates.length} : {}),
    failed: Boolean(value.error), summary: value.error ? failureMessage(value) : value.title || value.name || '已读取业务结果',
    sources,
  };
}
