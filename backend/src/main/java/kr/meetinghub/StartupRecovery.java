package kr.meetinghub;

import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class StartupRecovery implements ApplicationRunner {
    final MeetingService service;final TransactionTemplate tx;final LegacyExampleCleanup cleanup;
    StartupRecovery(MeetingService service,TransactionTemplate tx,LegacyExampleCleanup cleanup){this.service=service;this.tx=tx;this.cleanup=cleanup;}
    public void run(ApplicationArguments args){
        cleanup.run();
        tx.executeWithoutResult(s->{
            service.epics().findAll().stream().filter(e->e.status.equals("PUBLISHING")).forEach(e->{e.status="UNKNOWN";e.error="서버 재시작 전 에픽 등록 결과를 확인하세요. 자동 재생성하지 않습니다.";service.epics().save(e);});
            service.drafts().findAll().stream().filter(d->d.status.equals("PUBLISHING")).forEach(d->{
                d.status="UNKNOWN";d.error="서버 재시작 전 등록 결과를 Jira에서 확인하세요. 자동 재생성하지 않습니다.";service.drafts().save(d);
            });
            service.jobs().findAll().stream().filter(j->List.of("QUEUED","RUNNING").contains(j.status)).forEach(j->{
                j.status="FAILED";j.errorMessage="서버 재시작으로 중단되었습니다. 재시도하세요.";service.jobs().save(j);
                var m=service.lock(j.meetingId);m.status="FAILED";service.meetings().save(m);
            });
        });
    }
}
