package com.jsd.aird.rnd.application;

import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.rnd.application.port.ResearchTestRepository;
import com.jsd.aird.rnd.domain.ResearchTestModels.Type;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ResearchTestServiceTest {
    private final ResearchTestService service = new ResearchTestService(mock(ResearchTestRepository.class), mock(FileStorageFacade.class));
    ResearchTestServiceTest(){ActorContext.set(new Actor(UUID.randomUUID(),UUID.randomUUID(),"tester"));}
    @AfterEach void clear(){ActorContext.clear();}
    @Test void requiresStandardNumber(){assertThatThrownBy(()->service.create(new ResearchTestService.CreateCommand(Type.STANDARD,null,"标准",null,null,null, LocalDate.now(),"WORD","BLANK","ALL",null,null,null,null,null,null,null,null,null,null,null))).isInstanceOf(ApiException.class).hasMessageContaining("标准编号");}
    @Test void rejectsUnsupportedUpload(){assertThatThrownBy(()->service.upload(new ResearchTestService.UploadCommand(UUID.randomUUID(),"payload.exe","application/octet-stream",12,"hash",null,null,"ALL",null,null,null))).isInstanceOf(ApiException.class).hasMessageContaining("不支持");}
    @Test void rejectsEmptyUpload(){assertThatThrownBy(()->service.upload(new ResearchTestService.UploadCommand(UUID.randomUUID(),"report.pdf","application/pdf",0,"hash",null,null,"ALL",null,null,null))).isInstanceOf(ApiException.class).hasMessageContaining("不能为空");}
}
