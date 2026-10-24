package com.jsd.aird.shared.json;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RevisionTokenTest {
    @Test void contentOrderDoesNotChangeARevisionButChangedValuesDo() throws Exception {
        var json=new ObjectMapper();var a=RevisionToken.of("id",json.readTree("{\"a\":1,\"b\":2}"));
        assertThat(a).isEqualTo(RevisionToken.of("id",json.readTree("{\"b\":2,\"a\":1}")));
        assertThat(a).isNotEqualTo(RevisionToken.of("id",json.readTree("{\"a\":3,\"b\":2}")));
    }
    @Test void missingAndStaleTokensCannotBypassTheGuard(){
        for(String missing:new String[]{null,"","undefined","a"})assertThatThrownBy(()->RevisionToken.require(missing,"a".repeat(64))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(()->RevisionToken.require("b".repeat(64),"a".repeat(64))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));
        assertThatCode(()->RevisionToken.require("a".repeat(64),"a".repeat(64))).doesNotThrowAnyException();
    }
}
