package kr.meetinghub;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:recovery;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
@Import(PublisherRecoveryTest.Config.class)
class PublisherRecoveryTest {
    @Autowired MeetingService service;@Autowired Publisher publisher;@Autowired UncertainJira jira;
    @TestConfiguration static class Config {@Bean @Primary UncertainJira testJira(){return new UncertainJira();}}
    static class UncertainJira implements Adapters.Jira {
        final AtomicInteger creates=new AtomicInteger();boolean indexed;
        public Result create(Draft d){creates.incrementAndGet();throw new CloudJira.Uncertain();}
        public Optional<Result> find(Draft d){return indexed?Optional.of(new Result(d.id,"PUBLISHED","TEST-42","https://example.atlassian.net/browse/TEST-42",null)):Optional.empty();}
        public String target(){return "cloud:https://example.atlassian.net";}
    }
    @Test void uncertainRequestsNeverRecreateAndCanReconcileLater() {
        var m=service.create(new CreateMeeting("대사 테스트","",List.of(),"TEST","","확인하세요.","TEXT","ko",false,"일반"));
        var d=service.addDraft(m.id(),new DraftInput("TEST","Task","생성 결과 확인","검증용 설명","","","",List.of(),"",List.of()));
        service.approve(List.of(d.id));
        assertThat(publisher.publish(d.id).status()).isEqualTo("UNKNOWN");
        assertThat(service.drafts().findById(d.id).orElseThrow().status).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->service.update(d.id,new DraftInput("TEST","Task","변경","설명","","","",List.of(),"",List.of()))).hasMessageContaining("수정할 수 없습니다");
        assertThat(publisher.publish(d.id).status()).isEqualTo("UNKNOWN");assertThat(jira.creates.get()).isEqualTo(1);
        jira.indexed=true;assertThat(publisher.publish(d.id).issueKey()).isEqualTo("TEST-42");
        assertThat(publisher.publish(d.id).status()).isEqualTo("SKIPPED");assertThat(jira.creates.get()).isEqualTo(1);
    }
}
