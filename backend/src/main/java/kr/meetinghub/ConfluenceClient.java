package kr.meetinghub;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;

/** Confluence Cloud v2. Redirects and pagination hosts are never followed with credentials. */
@Component
public class ConfluenceClient {
    interface Transport { HttpResponse<String> send(HttpRequest request)throws Exception; }
    final ObjectMapper json;final Transport transport;
    @org.springframework.beans.factory.annotation.Autowired
    ConfluenceClient(ObjectMapper json){this(json,productionTransport());}
    static Transport productionTransport(){var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();return request->client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    ConfluenceClient(ObjectMapper json,Transport transport){this.json=json;this.transport=transport;}
    public record Space(String id,String name,String key){}
    public record Page(String id,String title,String spaceId,String parentId,int version,String body){}
    public record Listing<T>(List<T> results,String nextCursor){}
    static final class RemoteFailure extends IllegalArgumentException {
        final boolean uncertain;RemoteFailure(String message,boolean uncertain){super(message);this.uncertain=uncertain;}
    }
    static String q(String value){return URLEncoder.encode(Objects.toString(value,""),StandardCharsets.UTF_8);}
    static String id(String value){MeetingService.require(value!=null&&value.matches("[0-9]+"),"Confluence ID 형식을 확인하세요.");return value;}
    JsonNode request(ConfluenceSettings.Config config,String method,String path,Object body){
        var c=config.credentials();MeetingService.require(config.connected(),"Confluence 연결을 먼저 설정하세요.");c.apiBase();
        String base=c.cloudId().isBlank()?c.siteUrl()+"/wiki/api/v2":"https://api.atlassian.com/ex/confluence/"+c.cloudId()+"/wiki/api/v2";
        try{
            var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(45)).header("Accept","application/json")
                .header("Authorization","Basic "+Base64.getEncoder().encodeToString((c.email()+":"+c.apiToken()).getBytes(StandardCharsets.UTF_8)));
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8));
            if(body!=null)builder.header("Content-Type","application/json");
            var response=transport.send(builder.build());int status=response.statusCode();
            if(status<200||status>=300)throw new RemoteFailure(status==401||status==403?"Confluence 인증 또는 스페이스 접근 권한을 확인하세요.":status==409?"Confluence 문서가 다른 곳에서 변경되었습니다. 다시 동기화하세요.":"Confluence 요청에 실패했습니다. (HTTP "+status+")",method.equals("POST")&&status>=500);
            return json.readTree(response.body());
        }catch(RemoteFailure e){throw e;}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new RemoteFailure("Confluence 응답을 확인할 수 없습니다. 연결을 확인한 뒤 다시 동기화하세요.",!method.equals("GET"));}
    }
    Space space(JsonNode n){return new Space(n.path("id").asText(),n.path("name").asText(),n.path("key").asText());}
    Page page(JsonNode n){return new Page(n.path("id").asText(""),n.path("title").asText(""),n.path("spaceId").asText(""),n.path("parentId").asText(""),n.path("version").path("number").asInt(),n.path("body").path("storage").path("value").asText(""));}
    String cursor(JsonNode response){
        String next=response.path("_links").path("next").asText("");if(next.isBlank())return "";
        try{String query=URI.create(next).getRawQuery();if(query!=null)for(String pair:query.split("&"))if(pair.startsWith("cursor="))return URLDecoder.decode(pair.substring(7),StandardCharsets.UTF_8);}catch(Exception ignored){}return "";
    }
    public Listing<Space> spaces(ConfluenceSettings.Config c,String cursor){var n=request(c,"GET","/spaces?limit=50"+(cursor.isBlank()?"":"&cursor="+q(cursor)),null);var items=new ArrayList<Space>();n.path("results").forEach(x->items.add(space(x)));return new Listing<>(items,cursor(n));}
    public Space space(ConfluenceSettings.Config c,String spaceId){return space(request(c,"GET","/spaces/"+id(spaceId),null));}
    public Listing<Page> pages(ConfluenceSettings.Config c,String spaceId,String title,String cursor){var n=request(c,"GET","/spaces/"+id(spaceId)+"/pages?status=current&limit=50&body-format=storage"+(title.isBlank()?"":"&title="+q(title))+(cursor.isBlank()?"":"&cursor="+q(cursor)),null);var items=new ArrayList<Page>();n.path("results").forEach(x->items.add(page(x)));return new Listing<>(items,cursor(n));}
    public Page page(ConfluenceSettings.Config c,String pageId){return page(request(c,"GET","/pages/"+id(pageId)+"?body-format=storage",null));}
    public Page create(ConfluenceSettings.Config c,String title,String html){
        var body=new LinkedHashMap<String,Object>();body.put("spaceId",c.spaceId());body.put("status","current");body.put("title",title);body.put("body",Map.of("representation","storage","value",html));if(!c.parentPageId().isBlank())body.put("parentId",c.parentPageId());
        return page(request(c,"POST","/pages"+(c.parentPageId().isBlank()?"?root-level=true":""),body));
    }
    public Page update(ConfluenceSettings.Config c,Page page,String html){return page(request(c,"PUT","/pages/"+id(page.id()),Map.of("id",page.id(),"status","current","title",page.title(),"body",Map.of("representation","storage","value",html),"version",Map.of("number",page.version()+1))));}
}
