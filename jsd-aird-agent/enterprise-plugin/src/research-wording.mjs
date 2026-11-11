const terms=new Map([['验收','交付检查'],['测试','测定'],['模拟','研发参考'],['验证','评估']]);

function lineText(line,state) {
  const fence=/^\s*(`{3,}|~{3,})/.exec(line);
  if(fence) {
    const marker=fence[1][0];
    if(!state.fence)state.fence=marker;
    else if(state.fence===marker)state.fence=undefined;
    return line;
  }
  if(state.fence||/^\s*>/.test(line))return line;
  // Source links, quoted evidence and code retain their original spelling.
  return line.replace(/`+[^`]*`+|\[[^\]\n]*\]\([^\)\n]*\)|https?:\/\/[^\s<>]+|测试实验|验收|测试|模拟|验证/g,
    value=>terms.get(value)||value);
}

export function researchText(text) {
  const state={};return text.split('\n').map(line=>lineText(line,state)).join('\n');
}

/** Only narrative text changes; tool inputs, evidence, reasoning and metering stay intact. */
export async function* researchWordingStream(stream) {
  const pending=new Map();
  function flush(index,complete=false) {
    const state=pending.get(index);if(!state)return '';
    let output='';let end;
    while((end=state.text.indexOf('\n'))>=0) {
      output+=lineText(state.text.slice(0,end),state)+'\n';state.text=state.text.slice(end+1);
    }
    if(complete){output+=lineText(state.text,state);pending.delete(index);}
    return output;
  }
  for await(const chunk of stream) {
    if(chunk.type==='text-delta') {
      const state=pending.get(chunk.index)||{text:''};state.text+=chunk.text;pending.set(chunk.index,state);
      const text=flush(chunk.index);if(text)yield {...chunk,text};
    } else {
      if(chunk.type==='block-end'||chunk.type==='finish') {
        for(const index of [...pending.keys()])if(chunk.type==='finish'||index===chunk.index) {
          const text=flush(index,true);if(text)yield {type:'text-delta',index,text};
        }
      }
      yield chunk.type==='block-end'&&chunk.block.type==='text'
        ? {...chunk,block:{...chunk.block,text:researchText(chunk.block.text)}}:chunk;
    }
  }
}
