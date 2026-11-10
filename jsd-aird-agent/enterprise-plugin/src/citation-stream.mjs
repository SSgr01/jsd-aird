export function citationText(text, links) {
  text=text.replace(/\[\s*E:\s*([^\]\n]*)\]|〔\s*E:\s*([^〕\n]*)〕/g,(original,ascii,localized)=>{
    const body=ascii ?? localized;
    const handles=body.split(/\s*[,，、;；]\s*E:\s*/).map(id=>id.trim());
    if(handles.length>1 && handles.every(id=>/^[eq][0-9a-f]{24}$/.test(id) && links[id]))
      return handles.map(id=>'[E:'+id+']').join('');
    const annotated=/^([eq][0-9a-f]{24})\s+(所在段落)$/.exec(body.trim());
    if(annotated && links[annotated[1]])return '[E:'+annotated[1]+'] '+annotated[2];
    const id=body.trim();
    return /^[eq][0-9a-f]{24}$/.test(id) && links[id] ? '[E:'+id+']' : original;
  });
  return text.replace(/\[E:([eq][0-9a-f]{24})\]|〔E:([eq][0-9a-f]{24})〕/g,(original,ascii,localized)=> {
    const id=ascii || localized;
    const link=links[id];
    return link ? '['+link.label+']('+link.url+(link.url.includes('#')?'&':'#')+'evidence='+(link.evidence_id || id)+')' : original;
  });
}

/** Native Markdown links; leave reasoning, tool arguments, usage and unknown references intact. */
export async function* citationStream(stream,links) {
  const pending=new Map();
  for await(const chunk of stream) {
    if(chunk.type==='text-delta') {
      let text=(pending.get(chunk.index)||'')+chunk.text;
      const start=Math.max(text.lastIndexOf('['),text.lastIndexOf('〔')), tail=start<0?'':text.slice(start);
      const hold=tail.length<=1024 && (/^[\[〔]\s*(?:E:?)?$/.test(tail)||/^[\[〔]\s*E:[^\]〕\n]*$/.test(tail));
      pending.set(chunk.index,hold?tail:'');
      if(hold)text=text.slice(0,start);
      if(text)yield {...chunk,text:citationText(text,links)};
    } else {
      if(chunk.type==='block-end'||chunk.type==='finish') {
        for(const [index,text] of pending) if(text&&(chunk.type==='finish'||index===chunk.index)) {
          yield {type:'text-delta',index,text};pending.delete(index);
        }
      }
      yield chunk.type==='block-end'&&chunk.block.type==='text'
        ? {...chunk,block:{...chunk.block,text:citationText(chunk.block.text,links)}} : chunk;
    }
  }
}
