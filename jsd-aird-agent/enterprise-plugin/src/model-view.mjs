/** Model/UI projections of business results. Authority stays in the business service. */
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
  if (Array.isArray(value)) return value.map(modelResult);
  if (!value || typeof value !== 'object') return value;
  const output = {};
  for (const [key,item] of Object.entries(value)) {
    if (['workspace_path','content_sha256','retrieval_trace','source_refs','citation_links'].includes(key)) continue;
    if (['candidates','context_candidates'].includes(key) && Array.isArray(item)) {
      output[key] = item.map(candidate => ({ evidence_id: candidate.evidence_id,
        ...sourceLabel(candidate.source), offset: candidate.node_offset, text: candidate.text,
        ...(value.citation_links?.[candidate.evidence_id]?.url
          ? {url:value.citation_links[candidate.evidence_id].url} : {}),
        ...(candidate.quote_id ? {quote_id:candidate.quote_id} : {}),
        ...Object.fromEntries(['record_key','field','value','unit','value_type','condition','source_status']
          .filter(key=>candidate[key]!==undefined).map(key=>[key,candidate[key]])),
      }));
    } else if (key === 'source') output[key] = sourceLabel(item);
    else if (key === 'trace') output[key] = Object.fromEntries(['mode','total_nodes','offset','read_at','status','next_cursor','total','section']
      .filter(name => item?.[name] !== undefined).map(name => [name,item[name]]));
    else output[key] = modelResult(item);
  }
  return output;
}
export function presentation(name, value) {
  const links = (value.candidates || []).flatMap(candidate => {
    const link = value.citation_links?.[candidate.evidence_id];
    return link ? [{label: candidate.source?.title ? `${candidate.source.title} · ${link.label}` : link.label, url:link.url}] : [];
  });
  const sources = [...new Map(links.map(link => [link.url, link])).values()].slice(0,8);
  return { enterprise: true, name, ...(Array.isArray(value.candidates) ? {count:value.candidates.length} : {}),
    failed: Boolean(value.error), summary: value.error ? '业务操作未完成' : value.title || value.name || '已读取业务结果',
    sources,
  };
}
