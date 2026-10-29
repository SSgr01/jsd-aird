package com.jsd.aird.shared.json;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CanonicalDigestTest {
    @Test void streamingDigestMatchesTheExistingCanonicalBytesIncludingChineseNumbersAndNull()throws Exception{
        var json=new ObjectMapper();var legacy=new JsonCanonicalizer(json);
        for(String source:new String[]{"null","{}","{\"z\":null,\"中文\":\"换行\\n与引号\\\"\",\"a\":[1,1.0,-0.5,1e20,{\"b\":false,\"a\":true}]}","[{},[],null,\"\\t\"]"}){
            var node=json.readTree(source);var expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(legacy.canonicalize(node))));
            assertThat(CanonicalDigest.sha256(json,node)).isEqualTo(expected);
        }
    }
}
