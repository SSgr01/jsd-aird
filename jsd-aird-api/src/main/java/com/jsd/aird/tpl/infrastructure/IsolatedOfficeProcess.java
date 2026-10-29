package com.jsd.aird.tpl.infrastructure;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jsd.aird.shared.resource.*;
import com.jsd.aird.tpl.application.port.*;
import java.io.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Pure file protocol shared by all document parsers. */
@Component @Primary
public class IsolatedOfficeProcess implements WordDocumentParser,WordOoxmlPatcher {
    private final IsolatedDocumentTasks tasks;
    @org.springframework.beans.factory.annotation.Autowired
    public IsolatedOfficeProcess(IsolatedDocumentTasks tasks){this.tasks=tasks;}
    public IsolatedOfficeProcess(ObjectMapper json,TemporarySpace space,IsolatedProcessRunner runner){this(new IsolatedDocumentTasks(json,space,runner));}
    @Override public OfficeStructureParser.ParseResult parse(InputStream input){return tasks.execute("parse",input,null,OfficeStructureParser.ParseResult.class);}
    @Override public byte[] apply(byte[] source,ArrayNode operations){return tasks.execute("patch",new ByteArrayInputStream(source),operations,byte[].class);}
    @Override public byte[] apply(InputStream source,ArrayNode operations,String expectedSha){return tasks.executeVerified("patch",source,operations,byte[].class,expectedSha);}
    @Override public byte[] applySnapshot(InputStream source,JsonNode snapshot,String expectedSha){return tasks.executeVerified("snapshot",source,snapshot,byte[].class,expectedSha);}
    @Override public byte[] applySnapshot(byte[] source,JsonNode snapshot){return tasks.execute("snapshot",new ByteArrayInputStream(source),snapshot,byte[].class);}
}
