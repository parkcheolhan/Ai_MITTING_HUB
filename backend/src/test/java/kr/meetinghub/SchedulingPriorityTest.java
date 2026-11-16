package kr.meetinghub;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SchedulingPriorityTest {
    @Test void absentEmptyAndMissingAllowedPrioritiesUseInternalPlanningOnly() throws Exception {
        for(String field:List.of("",",{\"fieldId\":\"priority\",\"name\":\"Priority\",\"allowedValues\":[]}",",{\"fieldId\":\"priority\",\"name\":\"Priority\"}")) {
            var fixture=new CloudJiraTest();var body=new AtomicReference<String>();
            var jira=new CloudJira(fixture.config(),fixture.json,(method,uri,auth,payload)->{
                if(method.equals("POST")){body.set(payload);return new CloudJira.Reply(201,"{\"key\":\"TEST-1\"}");}
                if(uri.getPath().endsWith("/10001"))return new CloudJira.Reply(200,fixture.metadata().replace("],\"isLast\"",field+"],\"isLast\""));
                return fixture.reads(uri);
            });
            var draft=fixture.draft();draft.priority="Medium";
            var catalog=SchedulingService.priorityCatalog(jira,"TEST",draft);
            assertThat(catalog.writable()).isFalse();assertThat(catalog.values()).extracting(Domain.SchedulePriority::id).containsExactly("Highest","High","Medium","Low","Lowest");
            jira.create(draft);assertThat(new ObjectMapper().readTree(body.get()).path("fields").has("priority")).isFalse();
        }
    }
    @Test void supportedPrioritiesKeepRealJiraIds() {
        var fixture=new CloudJiraTest();
        var jira=new CloudJira(fixture.config(),fixture.json,(method,uri,auth,payload)->{
            if(uri.getPath().endsWith("/10001"))return new CloudJira.Reply(200,fixture.metadata().replace("],\"isLast\"",",{\"fieldId\":\"priority\",\"allowedValues\":[{\"id\":\"2\",\"name\":\"High\"}]}],\"isLast\""));
            return fixture.reads(uri);
        });
        var catalog=SchedulingService.priorityCatalog(jira,"TEST",fixture.draft());
        assertThat(catalog.writable()).isTrue();assertThat(catalog.values()).containsExactly(new Domain.SchedulePriority("2","High"));
    }
}
