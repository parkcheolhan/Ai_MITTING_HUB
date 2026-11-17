package kr.meetinghub;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.meetinghub.Domain.*;

/** Transfers meeting minutes and original media after the Epic key is durable. */
@Service
public class EpicContents {
    final Epics epics;final Meetings meetings;final EpicAttachments attachments;final TransactionTemplate tx;final ObjectMapper json;final Path uploadDir;
    EpicContents(Epics epics,Meetings meetings,EpicAttachments attachments,TransactionTemplate tx,ObjectMapper json,@Value("${app.upload-dir}") String uploadDir){
        this.epics=epics;this.meetings=meetings;this.attachments=attachments;this.tx=tx;this.json=json;this.uploadDir=Path.of(uploadDir).toAbsolutePath().normalize();
    }
    static String description(String title,Content c){
        TimeReferences.clean(c.minutes);
        var text=new StringBuilder("회의록: ").append(title).append("\n회의 일시: ").append(Objects.toString(c.occurredAt,""))
            .append("\n참석자: ").append(String.join(", ",c.participants));
        if(c.minutes==null)return text.append("\n\n회의록이 아직 작성되지 않았습니다.").toString();
        var m=c.minutes;
        if(m.formatted!=null&&!m.formatted.isBlank())return text.append("\n\n").append(m.formatted).toString();
        section(text,"개요",m.overview);section(text,"요약",m.summary);section(text,"논의 내용",m.discussion);
        section(text,"결정 사항",m.decisions);section(text,"미결 사항",m.openQuestions);section(text,"후속 조치",m.followUp);
        return text.toString();
    }
    static void section(StringBuilder text,String heading,String value){text.append("\n\n").append(heading).append("\n").append(value==null||value.isBlank()?"기록 없음":value);}
    public synchronized Epic sync(String id,Adapters.Jira jira){
        var epic=epics.findById(id).orElseThrow(MeetingService::missing);
        MeetingService.require(epic.status.equals("PUBLISHED")&&epic.target.equals(jira.target()),"에픽의 등록 상태와 Jira 대상을 확인하세요.");
        if(epic.external)return epic;
        try{
            var meeting=meetings.findById(epic.meetingId).orElseThrow(MeetingService::missing);
            var content=json.readValue(meeting.payload,Content.class);
            String description=description(meeting.title,content);
            jira.updateDescription(epic.issueKey,description);
            for(var asset:content.assets)transfer(epic,asset,jira);
            return tx.execute(s->{var e=epics.locked(id).orElseThrow();e.description=description;e.contentStatus="SYNCED";e.contentError=null;return epics.save(e);});
        }catch(Exception e){
            String message=e instanceof IllegalArgumentException?e.getMessage():e instanceof CloudJira.Uncertain?"녹음 첨부 결과 확인이 필요합니다. Jira에 반영되면 다시 동기화하여 확인하세요. 확인 전에는 자동 재전송하지 않습니다.":"에픽 회의록·녹음 동기화에 실패했습니다. 서버 파일과 Jira 연결을 확인하세요.";
            return tx.execute(s->{var saved=epics.locked(id).orElseThrow();saved.contentStatus="FAILED";saved.contentError=message;return epics.save(saved);});
        }
    }
    void transfer(Epic epic,Asset asset,Adapters.Jira jira)throws Exception{
        MeetingService.require(asset.storageKey()!=null&&asset.storageKey().matches("[a-zA-Z0-9-]+\\.(mp3|wav|m4a|aac|acc|mp4|webm)"),"녹음 파일 경로를 확인하세요.");
        Path file=uploadDir.resolve(asset.storageKey()).normalize();
        MeetingService.require(file.startsWith(uploadDir)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)==asset.size(),"등록한 녹음 원본을 서버에서 찾을 수 없거나 크기가 변경되었습니다.");
        String id=UUID.nameUUIDFromBytes((epic.id+"|"+asset.id()).getBytes(StandardCharsets.UTF_8)).toString();
        String original=Objects.toString(asset.originalName(),"recording").replaceAll("[\\p{Cntrl}\\\"\\\\/]","_");
        if(original.length()>140)original=original.substring(original.length()-140);
        String filename="meeting-"+asset.id()+"-"+original;
        var claim=tx.execute(s->{
            epics.locked(epic.id).orElseThrow();
            var row=attachments.findById(id).orElseGet(()->{var a=new EpicAttachment();a.id=id;a.epicId=epic.id;a.assetId=asset.id();return a;});
            String previous=row.status;
            if(Set.of("PENDING","FAILED").contains(previous)){row.status="PUBLISHING";attachments.saveAndFlush(row);}
            return previous;
        });
        if(claim.equals("PUBLISHED"))return;
        boolean send=Set.of("PENDING","FAILED").contains(claim);
        try{
            if(!jira.attachmentExists(epic.issueKey,filename,asset.size())){
                if(!send)throw new CloudJira.Uncertain();
                jira.attach(epic.issueKey,file,filename,asset.mediaType());
            }
            attachmentStatus(id,"PUBLISHED");
        }catch(CloudJira.Uncertain e){attachmentStatus(id,"UNKNOWN");throw e;}
        catch(Exception e){attachmentStatus(id,send?"FAILED":"UNKNOWN");throw e;}
    }
    void attachmentStatus(String id,String status){tx.executeWithoutResult(s->{var a=attachments.findById(id).orElseThrow();a.status=status;attachments.save(a);});}
}
@Entity @Table(name="epic_attachments")
class EpicAttachment {
    @Id public String id;
    public String epicId,assetId,status="PENDING";
    @Version public long version;
}
interface EpicAttachments extends JpaRepository<EpicAttachment,String>{}
