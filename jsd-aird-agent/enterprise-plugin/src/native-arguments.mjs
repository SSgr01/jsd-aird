/** Decode one JSON-encoded collection without guessing paths or delivery intent. */
export function normalizePresentArguments(raw) {
  try {
    const args=JSON.parse(raw);
    if(!args || typeof args!=='object' || Array.isArray(args) || typeof args.files!=='string')return raw;
    const files=JSON.parse(args.files);
    if(!Array.isArray(files) || !files.length || files.length>8 || files.some(file=>
      !file || typeof file!=='object' || Array.isArray(file) || typeof file.path!=='string' || !file.path.trim()
      || Object.keys(file).some(key=>!['path','description'].includes(key))
      || ('description' in file && typeof file.description!=='string')))return raw;
    return JSON.stringify({...args,files});
  } catch { return raw; }
}

/** Canonicalize before the executor freezes arguments; keep live and stored calls aligned. */
export async function* nativeArgumentsStream(stream) {
  const pending=new Map();
  for await(const chunk of stream) {
    if(chunk.type==='tool-call-delta') {
      const state=pending.get(chunk.index);
      if(state || chunk.name==='present') {
        const value=state || {chunks:[]};
        value.chunks.push(chunk);pending.set(chunk.index,value);
        continue;
      }
    }
    if(chunk.type==='block-end' && pending.has(chunk.index)) {
      const {chunks}=pending.get(chunk.index);pending.delete(chunk.index);
      const raw=chunk.block.type==='tool-call' && chunk.block.name==='present' ? chunk.block.arguments : undefined;
      const normalized=raw===undefined ? raw : normalizePresentArguments(raw);
      if(normalized!==raw) {
        yield {type:'tool-call-delta',index:chunk.index,id:chunk.block.id,name:'present',argumentsDelta:normalized};
        yield {...chunk,block:{...chunk.block,arguments:normalized}};
        continue;
      }
      for(const original of chunks)yield original;
    }
    if(chunk.type==='finish') {
      for(const {chunks} of pending.values())for(const original of chunks)yield original;
      pending.clear();
    }
    yield chunk;
  }
  for(const {chunks} of pending.values())for(const original of chunks)yield original;
}
