package com.jsd.aird.rnd.application;

import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Reads CSV content without trimming values, splitting quoted lines, or truncating records. */
final class ExperimentCsvContent {
    static List<List<String>> read(byte[] bytes) {
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { text = new String(bytes, Charset.forName("GB18030")); }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var rows = new ArrayList<List<String>>(); var row = new ArrayList<String>();
        var cell = new StringBuilder(); boolean quoted = false, closed = false;
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (quoted) {
                if (c=='"') {
                    if (i+1<text.length() && text.charAt(i+1)=='"') {cell.append('"');i++;}
                    else {quoted=false;closed=true;}
                } else cell.append(c);
            } else if (c==',' || c=='\r' || c=='\n') {
                row.add(cell.toString());cell.setLength(0);closed=false;
                if (c!=',') {rows.add(row);row=new ArrayList<>();if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n')i++;}
            } else if (c=='"' && cell.isEmpty() && !closed) quoted=true;
            else if (closed || c=='"') throw new IllegalArgumentException("CSV 引号格式不正确");
            else cell.append(c);
        }
        if (quoted) throw new IllegalArgumentException("CSV 引号未闭合");
        if (!row.isEmpty() || !cell.isEmpty() || closed) {row.add(cell.toString());rows.add(row);}
        return rows;
    }
}
