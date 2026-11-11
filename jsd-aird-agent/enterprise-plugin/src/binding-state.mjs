import {mergeBusinessContext} from './business-context.mjs';

export function bindingCursor(previous,turn,hasInput=false) {
  return {binding_protocol:2,...(!hasInput && previous?.dsh_turn===turn ? {
    binding_etags:previous.binding_etags || {},known_citation_evidence_ids:previous.citation_evidence_ids || [],
  } : {})};
}

export function mergeBinding(previous,current,hasInput=false) {
  if(current.binding_protocol!==2)return !hasInput && previous?.id===current.id
    ? {...current,context:mergeBusinessContext(previous.context,current.context)} : current;
  if(!previous || previous.id!==current.id || previous.dsh_turn!==current.dsh_turn) return current;
  return {...previous,...current,
    citation_links:{...previous.citation_links,...current.citation_links},
    citation_evidence_ids:[...new Set([...(previous.citation_evidence_ids || []),...(current.citation_evidence_ids || [])])],
    context:current.context===undefined ? previous.context
      : hasInput ? current.context : mergeBusinessContext(previous.context,current.context)};
}
