package kr.meetinghub;

import java.util.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class ConfluenceApi {
    final ConfluenceSettings settings;final ConfluenceClient client;final ConfluencePublisher publisher;
    ConfluenceApi(ConfluenceSettings settings,ConfluenceClient client,ConfluencePublisher publisher){this.settings=settings;this.client=client;this.publisher=publisher;}
    @GetMapping("/settings/confluence") public ResponseEntity<?> settings(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.view());}
    @PutMapping("/settings/confluence/connection") public ResponseEntity<?> connection(@Valid @RequestBody ConfluenceSettings.Connection input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.save(input));}
    @PutMapping("/settings/confluence/destination") public Map<String,Object> destination(@Valid @RequestBody ConfluenceSettings.Destination input){
        var c=settings.current();var space=client.space(c,input.spaceId());String parentId=Objects.toString(input.parentPageId(),"").strip();
        // Accept only a numeric ID or a page URL on the already configured Atlassian site.
        if(parentId.startsWith("https://")){
            var uri=java.net.URI.create(parentId);MeetingService.require(c.credentials().site().getHost().equals(uri.getHost())&&uri.getUserInfo()==null&&uri.getPort()==-1,"설정한 Confluence 사이트의 페이지 URL을 입력하세요.");
            var match=java.util.regex.Pattern.compile("/pages/([0-9]+)(?:/|$)").matcher(uri.getPath());
            if(match.find())parentId=match.group(1);else{var query=Objects.toString(uri.getQuery(),"");var m=java.util.regex.Pattern.compile("(?:^|&)pageId=([0-9]+)(?:&|$)").matcher(query);MeetingService.require(m.find(),"상위 페이지 URL에 페이지 ID가 없습니다. 페이지 ID를 입력하세요.");parentId=m.group(1);}
        }
        var parent=parentId.isBlank()?null:client.page(c,parentId);MeetingService.require(parent==null||parent.spaceId().equals(input.spaceId()),"선택한 스페이스에 속한 상위 페이지를 지정하세요.");return settings.destination(input,c,space,parent);
    }
    @GetMapping("/integrations/confluence/spaces") public ConfluenceClient.Listing<ConfluenceClient.Space> spaces(@RequestParam(defaultValue="") String cursor){return client.spaces(settings.current(),cursor);}
    @GetMapping("/integrations/confluence/spaces/{id}/pages") public ConfluenceClient.Listing<ConfluenceClient.Page> pages(@PathVariable String id,@RequestParam(defaultValue="") String title,@RequestParam(defaultValue="") String cursor){return client.pages(settings.current(),id,title,cursor);}
    @GetMapping("/meetings/{id}/confluence") public ConfluencePublisher.View status(@PathVariable String id){return publisher.view(id);}
    @PostMapping("/meetings/{id}/confluence/publish") public ConfluencePublisher.View publish(@PathVariable String id){return publisher.publish(id);}
}
