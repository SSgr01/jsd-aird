package com.jsd.aird.shared.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.jsd.aird.shared.resource.OperationBudget;
import java.io.*;
import java.security.*;
import java.util.*;

/** Same sorted-object UTF-8 bytes as the legacy canonical tree, without copying that tree. */
public final class CanonicalDigest {
    private CanonicalDigest(){ }
    public static String sha256(ObjectMapper mapper,JsonNode value){
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            try(var generator=mapper.getFactory().createGenerator(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){write(mapper,generator,value);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(IOException|NoSuchAlgorithmException error){throw new IllegalArgumentException("JSON cannot be canonicalized",error);}
    }
    private static void write(ObjectMapper mapper,JsonGenerator generator,JsonNode value)throws IOException{
        OperationBudget.check();
        if(value==null || value.isNull()){generator.writeNull();return;}
        if(value.isObject()){
            generator.writeStartObject();var names=new TreeSet<String>();value.fieldNames().forEachRemaining(names::add);
            for(String name:names){generator.writeFieldName(name);write(mapper,generator,value.get(name));}generator.writeEndObject();
        }else if(value.isArray()){
            generator.writeStartArray();for(var item:value)write(mapper,generator,item);generator.writeEndArray();
        }else mapper.writeValue(generator,value);
    }
}
