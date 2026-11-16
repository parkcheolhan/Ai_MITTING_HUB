package kr.meetinghub;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:cleanup;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional
class LegacyExampleCleanupTest {
    @Autowired MeetingService service;@Autowired LegacyExampleCleanup cleanup;
    Meeting fixture(String title,String transcript){
        var m=new Meeting();m.title=title;m.createdAt="2026-10-25T03:00:00Z";
        var c=new Content();c.segments=TranscriptParser.parse(transcript);service.save(m,c);return m;
    }
    @Test void removesOnlyRecognizedOldFixturesAndTheirChildrenOnce(){
        service.audits().deleteAll();
        var fake=fixture("AI UI 원문 검증","[00:12:34] 이지은: 운영 환경 권한을 확인하겠습니다.");
        var actual=fixture("실제로 등록한 회의","[00:12:34] 이지은: 운영 환경 권한을 확인하겠습니다.");
        var modified=fixture("AI UI 원문 검증","사용자가 수정한 실제 자료");
        var draft=new Draft();draft.meetingId=fake.id;service.drafts().save(draft);
        var job=new Job();job.meetingId=fake.id;service.jobs().save(job);
        service.audits().save(new Audit("DRAFT_CREATED",draft.id));
        cleanup.run();service.meetings().flush();
        assertThat(service.meetings().findById(fake.id)).isEmpty();
        assertThat(service.drafts().findByMeetingId(fake.id)).isEmpty();assertThat(service.jobs().findByMeetingIdOrderByCreatedAtDesc(fake.id)).isEmpty();
        assertThat(service.meetings().findById(actual.id)).isPresent();assertThat(service.meetings().findById(modified.id)).isPresent();
        var after=fixture("AI UI 원문 검증","[00:12:34] 이지은: 운영 환경 권한을 확인하겠습니다.");
        cleanup.run();assertThat(service.meetings().findById(after.id)).isPresent();
    }
    @Test void freshDatabaseDoesNotCreateExampleMeetings(){assertThat(service.meetings().count()).isZero();}
}
