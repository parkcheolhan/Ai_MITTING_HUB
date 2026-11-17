package kr.meetinghub;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import kr.meetinghub.Domain.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:epic-contents;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","app.upload-dir=./target/epic-content-uploads"})
class EpicContentsTest {
    @TestConfiguration static class Config {@Bean @Primary RecordingJira recordingJira(){return new RecordingJira();}}
    static class RecordingJira implements Adapters.Jira {
        List<Draft> created=new ArrayList<>();List<String> uploads=new ArrayList<>();Set<String> remote=new HashSet<>();String description="";boolean uncertain,denied;int sequence;
        public String target(){return "recording-test";}
        public Result create(Draft d){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();created.add(d);return new Result(d.id,"PUBLISHED","TEST-"+(++sequence),null,null);}
        public void updateDescription(String key,String text){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();description=text;}
        public boolean attachmentExists(String key,String filename,long size){return remote.contains(key+"|"+filename);}
        public void attach(String key,Path path,String filename,String mediaType){
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();uploads.add(filename);
            if(denied)throw new IllegalArgumentException("첨부 권한 없음");
            if(uncertain)throw new CloudJira.Uncertain();
            remote.add(key+"|"+filename);
        }
    }
    @Autowired MeetingService service;@Autowired Publisher publisher;@Autowired RecordingJira jira;@Autowired Epics epics;@Autowired EpicContents contents;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    final List<Path> files=new ArrayList<>();
    @BeforeEach void reset(){jira.created.clear();jira.uploads.clear();jira.remote.clear();jira.uncertain=false;jira.denied=false;}
    @AfterEach void cleanup()throws Exception{for(var file:files)Files.deleteIfExists(file);files.clear();}
    String meeting(){
        String id=service.create(new CreateMeeting("회의록 첨부 확인","2026-10-25T10:00",List.of("참석자"),"TEST","","회의를 진행합니다.","TEXT","ko",false,"일반")).id();
        var minutes=new Minutes();minutes.overview="개요 내용";minutes.summary="요약 내용";minutes.discussion="논의 내용";minutes.decisions="결정 내용";minutes.openQuestions="미결 내용";minutes.followUp="후속 내용";
        tx.executeWithoutResult(s->{var meeting=service.meetings().locked(id).orElseThrow();var content=service.content(meeting);content.minutes=minutes;service.save(meeting,content);});return id;
    }
    Asset asset(String id,String name)throws Exception{
        String key=UUID.randomUUID()+".m4a";Path file=Path.of("target/epic-content-uploads",key);Files.createDirectories(file.getParent());Files.write(file,new byte[]{1,2,3});files.add(file);
        var asset=new Asset(UUID.randomUUID().toString(),name,"audio/mp4",3,key);service.addAsset(id,asset);return asset;
    }
    Draft task(String id){var d=service.addDraft(id,new DraftInput("TEST","Task","업무","설명","","","",List.of(),"",List.of()));service.approve(List.of(d.id));return d;}
    @Test void publishesConfluenceReferenceAndAllRecordingsAndReusesAttachments()throws Exception{
        String id=meeting();asset(id,"회의 녹음.m4a");asset(id,"다음 녹음.m4a");var draft=task(id);
        assertThat(publisher.publishBatch(List.of(draft.id)).get(0).status()).isEqualTo("PUBLISHED");
        assertThat(jira.created.get(0).description).contains("Confluence","/#/meetings/"+id).doesNotContain("개요 내용","논의 내용");
        assertThat(jira.uploads).hasSize(2);assertThat(epics.findByMeetingId(id).get(0).contentStatus).isEqualTo("SYNCED");
        var minutes=service.get(id).content().minutes;minutes.decisions="수정된 결정";service.saveMinutes(id,minutes);
        contents.sync(epics.findByMeetingId(id).get(0).id,jira);
        assertThat(jira.description).contains("Confluence","/#/meetings/"+id).doesNotContain("수정된 결정");assertThat(jira.uploads).hasSize(2);
    }
    @Test void attachmentPermissionFailureBlocksTasksAndRetryReusesEpic()throws Exception{
        String id=meeting();asset(id,"원본.m4a");var draft=task(id);jira.denied=true;
        assertThat(publisher.publishBatch(List.of(draft.id)).get(0).status()).isEqualTo("BLOCKED");
        assertThat(jira.created).hasSize(1);assertThat(epics.findByMeetingId(id).get(0).contentError).contains("권한");
        jira.denied=false;assertThat(publisher.publishBatch(List.of(draft.id)).get(0).status()).isEqualTo("PUBLISHED");
        assertThat(jira.created).extracting(d->d.issueType).containsExactly("Epic","Task");
    }
    @Test void uncertainAttachmentOnlyReconcilesWithoutSendingAgain()throws Exception{
        String id=meeting();asset(id,"원본.m4a");var draft=task(id);jira.uncertain=true;
        assertThat(publisher.publishBatch(List.of(draft.id)).get(0).status()).isEqualTo("BLOCKED");
        var epic=epics.findByMeetingId(id).get(0);
        assertThat(publisher.publishBatch(List.of(draft.id)).get(0).status()).isEqualTo("BLOCKED");assertThat(jira.uploads).hasSize(1);
        jira.remote.add(epic.issueKey+"|"+jira.uploads.get(0));
        assertThat(publisher.publishBatch(List.of(draft.id)).get(0).status()).isEqualTo("PUBLISHED");assertThat(jira.uploads).hasSize(1);
    }
    @Test void textOnlyMeetingSyncsWithoutAttachingAnything(){
        String id=meeting();assertThat(publisher.publishBatch(List.of(task(id).id)).get(0).status()).isEqualTo("PUBLISHED");assertThat(jira.uploads).isEmpty();
    }
    @Test void missingOriginalBlocksTasksAndDifferentTargetCannotSync()throws Exception{
        String id=meeting();asset(id,"원본.m4a");Files.delete(files.get(0));
        assertThat(publisher.publishBatch(List.of(task(id).id)).get(0).status()).isEqualTo("BLOCKED");assertThat(jira.uploads).isEmpty();
        var epic=epics.findByMeetingId(id).get(0);
        assertThatThrownBy(()->contents.sync(epic.id,new Adapters.MockJira())).hasMessageContaining("Jira 대상");
    }
}
