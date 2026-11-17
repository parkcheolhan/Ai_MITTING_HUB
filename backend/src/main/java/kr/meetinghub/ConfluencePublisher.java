package kr.meetinghub;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims are committed before remote writes. Ambiguous creates only reconcile, never blindly resend. */
@Service
public class ConfluencePublisher {
    final ConfluencePublications rows;final ConfluenceSettings settings;final ConfluenceClient client;final MeetingService meetings;final TransactionTemplate tx;final String publicUrl;
    ConfluencePublisher(ConfluencePublications rows,ConfluenceSettings settings,ConfluenceClient client,MeetingService meetings,TransactionTemplate tx,@Value("${app.public-url:https://parkcheolhan.github.io/Ai_MITTING_HUB}") String publicUrl){this.rows=rows;this.settings=settings;this.client=client;this.meetings=meetings;this.tx=tx;this.publicUrl=publicUrl;}
    public record View(boolean configured,boolean automatic,String status,String pageUrl,String error,String syncedAt,String spaceName,String parentTitle){}
    String id(String meetingId,ConfluenceSettings.Config c){return UUID.nameUUIDFromBytes((meetingId+"|"+c.target()).getBytes(StandardCharsets.UTF_8)).toString();}
    public View view(String meetingId){meetings.get(meetingId);var c=settings.current();return view(c,rows.findById(id(meetingId,c)).orElse(null));}
    View view(ConfluenceSettings.Config c,ConfluencePublication row){return new View(c.configured(),c.automatic(),row==null?"NOT_PUBLISHED":row.status,row==null?"":Objects.toString(row.pageUrl,""),row==null?"":Objects.toString(row.error,""),row==null?"":Objects.toString(row.syncedAt,""),c.spaceName(),c.parentTitle());}
    public void autoSync(String meetingId){try{var c=settings.current();if(c.configured()&&c.automatic()&&meetings.get(meetingId).content().minutes!=null)publish(meetingId);}catch(Exception ignored){/* Publication state reports failure independently of successful minutes/ticket saves. */}}
    public synchronized View publish(String meetingId){
        var c=settings.current();MeetingService.require(c.configured(),"회의록 설정에서 Confluence 연결과 업로드 위치를 지정하세요.");
        var meeting=meetings.get(meetingId);MeetingService.require(meeting.content().minutes!=null,"회의록을 먼저 생성하거나 저장하세요.");
        String id=id(meetingId,c),html=ConfluenceDocument.render(meeting,publicUrl);
        record Claim(ConfluencePublication row,boolean reconcile){}
        var claim=tx.execute(s->{
            meetings.lock(meetingId);var row=rows.findById(id).orElseGet(()->{var r=new ConfluencePublication();r.id=id;r.meetingId=meetingId;r.title=ConfluenceDocument.title(meeting);return r;});
            boolean reconcile=Set.of("PUBLISHING","UNKNOWN").contains(row.status);
            row.status="PUBLISHING";row.error=null;rows.saveAndFlush(row);return new Claim(row,reconcile);
        });
        String pageId=Objects.toString(claim.row.pageId,"");boolean creationAttempted=false;
        try{
            // Validate destination again in case the parent was moved or deleted after settings were saved.
            client.space(c,c.spaceId());if(!c.parentPageId().isBlank())MeetingService.require(client.page(c,c.parentPageId()).spaceId().equals(c.spaceId()),"상위 페이지의 스페이스가 변경되었습니다. 업로드 위치를 다시 지정하세요.");
            ConfluenceClient.Page page=null;
            if(!pageId.isBlank())page=client.page(c,pageId);
            else{
                String cursor="";var visited=new HashSet<String>();
                do{var found=client.pages(c,c.spaceId(),claim.row.title,cursor);for(var candidate:found.results()){
                    if(!candidate.title().equals(claim.row.title))continue;
                    var full=client.page(c,candidate.id());
                    MeetingService.require(owns(full,meetingId,c),"같은 제목의 다른 Confluence 페이지가 있습니다. 해당 페이지의 제목 또는 업로드 위치를 변경하세요.");
                    MeetingService.require(page==null,"연결된 회의록이 여러 개입니다. Confluence에서 중복 문서를 확인하세요.");page=full;
                }cursor=found.nextCursor();MeetingService.require(cursor.isBlank()||visited.add(cursor),"Confluence 페이지 목록을 모두 확인할 수 없습니다. 다시 시도하세요.");}while(!cursor.isBlank());
                if(page==null){
                    MeetingService.require(!claim.reconcile,"이전 업로드 결과를 확인할 수 없습니다. 중복 생성을 방지하기 위해 자동 재전송하지 않습니다. Confluence에서 문서 생성 여부를 확인한 뒤 다시 동기화하세요.");
                    creationAttempted=true;page=client.create(c,claim.row.title,html);ConfluenceClient.id(page.id());pageId=page.id();
                    final String durablePageId=pageId;
                    tx.executeWithoutResult(s->{var r=rows.findById(id).orElseThrow();r.pageId=durablePageId;r.pageUrl=url(c,durablePageId);rows.saveAndFlush(r);});
                    // Successful POST already contains the complete document.
                    return success(id,c,pageId);
                }
            }
            MeetingService.require(owns(page,meetingId,c),"연결된 Confluence 문서가 이동되었거나 모아 연결 정보가 변경되었습니다. 업로드 위치와 문서를 확인하세요.");
            pageId=page.id();final String known=pageId;
            tx.executeWithoutResult(s->{var r=rows.findById(id).orElseThrow();r.pageId=known;r.pageUrl=url(c,known);rows.saveAndFlush(r);});
            client.update(c,page,html);return success(id,c,pageId);
        }catch(Exception e){
            boolean uncertain=claim.reconcile||e instanceof ConfluenceClient.RemoteFailure f&&f.uncertain||creationAttempted&&!(e instanceof ConfluenceClient.RemoteFailure);
            String error=e instanceof IllegalArgumentException?e.getMessage():"Confluence 동기화에 실패했습니다. 연결과 업로드 위치를 확인하세요.";
            var row=tx.execute(s->{var r=rows.findById(id).orElseThrow();r.status=uncertain?"UNKNOWN":"FAILED";r.error=error;return rows.saveAndFlush(r);});return view(c,row);
        }
    }
    boolean owns(ConfluenceClient.Page page,String meetingId,ConfluenceSettings.Config c){return page.spaceId().equals(c.spaceId())&&page.parentId().equals(c.parentPageId())&&page.body().contains(ConfluenceDocument.escape(ConfluenceDocument.meetingUrl(publicUrl,meetingId)));}
    String url(ConfluenceSettings.Config c,String pageId){return c.credentials().siteUrl()+"/wiki/pages/viewpage.action?pageId="+ConfluenceClient.id(pageId);}
    View success(String id,ConfluenceSettings.Config c,String pageId){var row=tx.execute(s->{var r=rows.findById(id).orElseThrow();r.pageId=pageId;r.pageUrl=url(c,pageId);r.status="PUBLISHED";r.error=null;r.syncedAt=Instant.now().toString();return rows.saveAndFlush(r);});return view(c,row);}
}
@Entity @Table(name="confluence_publications")
class ConfluencePublication {
    @Id public String id;
    public String meetingId,title,pageId,pageUrl,status="PENDING",syncedAt;
    @Column(length=2000) public String error;
    @Version public long version;
}
interface ConfluencePublications extends JpaRepository<ConfluencePublication,String>{}
