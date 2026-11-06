export function citationText(text, links) {
  return text.replace(/\[E:(e[0-9a-f]{24})\]/g,(original,id)=> {
    const link=links[id];
    return link ? '['+link.label+']('+link.url+(link.url.includes('#')?'&':'#')+'evidence='+id+')' : original;
  });
}

/** Native Markdown links; leave reasoning, tool arguments, usage and unknown references intact. */
export async function* citationStream(stream,links) {
  const pending=new Map();
  for await(const chunk of stream) {
    if(chunk.type==='text-delta') {
      let text=(pending.get(chunk.index)||'')+chunk.text;
      const start=text.lastIndexOf('['), tail=start<0?'':text.slice(start);
      const hold=tail.length<32 && (/^\[E?:?$/.test(tail)||/^\[E:e[0-9a-f]*$/.test(tail));
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
