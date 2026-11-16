package kr.meetinghub;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import java.nio.file.*;
import com.fasterxml.jackson.databind.*;
import kr.meetinghub.Domain.*;

public class CloudJira implements Adapters.Jira {
    public static class Uncertain extends RuntimeException {
        public Uncertain(String message) { super(message); }
        public Uncertain() { super("Jira 생성 결과를 확정할 수 없습니다. 중복 방지를 위해 재생성하지 않습니다. 잠시 후 결과 확인을 실행하세요."); }
    }
    public static class ReadFailure extends IllegalArgumentException {
        ReadFailure(String message) { super(message); }
    }
    record Reply(int status,String body) {}
    interface Transport { Reply send(String method,URI uri,String auth,String body) throws IOException,InterruptedException; }
    interface UploadTransport { Reply send(URI uri,String auth,Path file,String filename,String mediaType) throws IOException,InterruptedException; }
    final UploadTransport uploads;
    final JiraConfig config; final ObjectMapper json; final Transport transport;
    public CloudJira(JiraConfig config,ObjectMapper json) {
        this(config,json,defaultTransport());
    }
    CloudJira(JiraConfig config,ObjectMapper json,Transport transport) { this(config,json,transport,defaultUploads()); }
    CloudJira(JiraConfig config,ObjectMapper json,Transport transport,UploadTransport uploads) { this.config=config; this.json=json; this.transport=transport;this.uploads=uploads; }
    static UploadTransport defaultUploads(){
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (uri,auth,file,filename,mediaType)->{
            String boundary="meetinghub-"+UUID.randomUUID();
            String header="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+mediaType+"\r\n\r\n";
            var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(header.getBytes(StandardCharsets.UTF_8)),HttpRequest.BodyPublishers.ofFile(file),HttpRequest.BodyPublishers.ofByteArray(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8)));
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).header("Authorization",auth).header("Accept","application/json").header("X-Atlassian-Token","no-check").header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(),response.body());
        };
    }
    static void issueKey(String key){MeetingService.require(key!=null&&key.matches("[A-Z][A-Z0-9_]*-[0-9]+"),"Jira 이슈 키를 확인하세요.");}
    @Override public void updateDescription(String key,String description){
        issueKey(key);request("PUT","/issue/"+key,Map.of("fields",Map.of("description",adf(description))),false);
    }
    @Override public boolean attachmentExists(String key,String filename,long size){
        issueKey(key);var attachments=request("GET","/issue/"+key+"?fields=attachment",null,false).path("fields").path("attachment");
        MeetingService.require(attachments.isArray(),"Jira 첨부파일 목록을 확인할 수 없습니다.");
        for(var attachment:attachments)if(filename.equals(attachment.path("filename").asText())&&size==attachment.path("size").asLong(-1))return true;
        return false;
    }
    @Override public void attach(String key,Path file,String filename,String mediaType){
        issueKey(key);
        MeetingService.require(!filename.isBlank()&&!filename.matches("(?s).*[\\r\\n\\\"\\\\/].*")&&mediaType.matches("(?:audio|video)/[a-zA-Z0-9.+-]+"),"첨부파일 이름과 형식을 확인하세요.");
        var meta=request("GET","/attachment/meta",null,false);
        MeetingService.require(meta.path("enabled").asBoolean(),"Jira 첨부파일 기능을 활성화하세요.");
        try{
            MeetingService.require(Files.size(file)<=meta.path("uploadLimit").asLong(0),"녹음 파일이 Jira 첨부 용량 제한을 초과합니다. Jira 첨부 용량 설정을 확인하세요.");
            String auth="Basic "+Base64.getEncoder().encodeToString((config.email()+":"+config.apiToken()).getBytes(StandardCharsets.UTF_8));
            var reply=uploads.send(URI.create(config.apiBase()+"/issue/"+key+"/attachments"),auth,file,filename,mediaType);
            if(reply.status()>=500||reply.status()==408)throw new Uncertain();
            MeetingService.require(reply.status()>=200&&reply.status()<300,"Jira 첨부 HTTP "+reply.status()+": 첨부파일 생성 권한·토큰 스코프·용량 제한을 확인하세요.");
            var rows=json.readTree(reply.body());
            boolean found=false;if(rows.isArray())for(var row:rows)if(filename.equals(row.path("filename").asText())&&Files.size(file)==row.path("size").asLong(-1))found=true;
            if(!found)throw new Uncertain();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Uncertain();}
        catch(IOException e){throw new Uncertain();}
    }
    static Transport defaultTransport() {
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (method,uri,auth,body)->{
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                .header("Authorization",auth).header("Accept","application/json");
            if(body==null)request.GET();
            else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(),response.body());
        };
    }
    @Override public String mode() { return "cloud"; }
    @Override public String target() { return "cloud:"+config.siteUrl(); }
    JsonNode request(String method,String path,Object body,boolean creating) {
        MeetingService.require(config.missing().isEmpty(),"누락된 Jira 환경변수: "+String.join(", ",config.missing()));
        var base=config.apiBase();
        String auth="Basic "+Base64.getEncoder().encodeToString((config.email()+":"+config.apiToken()).getBytes(StandardCharsets.UTF_8));
        try {
            var reply=transport.send(method,URI.create(base+path),auth,body==null?null:json.writeValueAsString(body));
            if(reply.status()<200||reply.status()>=300) {
                if(creating&&(reply.status()>=500||reply.status()==408))throw new Uncertain();
                String hint=switch(reply.status()) {
                    case 401 -> "이메일·API 토큰·만료 여부·스코프 토큰의 Cloud ID를 확인하세요.";
                    case 403 -> "프로젝트 조회/이슈 생성 권한과 API 토큰 스코프를 확인하세요.";
                    case 404 -> notFoundHint(path);
                    case 429 -> "Jira 호출 제한입니다. 잠시 후 재시도하세요.";
                    default -> "프로젝트 필수 필드와 입력값을 확인하세요.";
                };
                throw new ReadFailure("Jira HTTP "+reply.status()+": "+hint);
            }
            if(reply.status()==204||reply.body()==null||reply.body().isBlank())return json.createObjectNode();
            try { return json.readTree(reply.body()); }
            catch(IOException e) { if(creating)throw new Uncertain(); throw new ReadFailure("Jira 응답 형식을 읽을 수 없습니다."); }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); if(creating)throw new Uncertain(); throw new ReadFailure("Jira 요청이 중단되었습니다.");
        } catch(IOException e) {
            if(creating)throw new Uncertain(); throw new ReadFailure("Jira 네트워크 연결에 실패했습니다. 주소와 네트워크를 확인하세요.");
        }
    }
    static String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    static String notFoundHint(String path){
        if(path.startsWith("/issue/")&&!path.startsWith("/issue/createmeta/"))return "연결할 이슈가 없거나 조회 권한이 없습니다. 이슈 키와 Jira 사이트를 확인하세요.";
        var match=java.util.regex.Pattern.compile("^/issue/createmeta/([A-Z][A-Z0-9_]{0,19})/issuetypes(?:/([0-9]+))?(?:\\?.*)?$").matcher(path);
        if(match.matches())return match.group(2)==null?"프로젝트 '"+match.group(1)+"'를 찾을 수 없거나 조회 권한이 없습니다. Jira 프로젝트 목록에서 실제 키를 선택하세요.":"프로젝트 '"+match.group(1)+"'의 이슈 유형 '"+match.group(2)+"'을 찾을 수 없습니다. 해당 프로젝트의 실제 이슈 유형을 선택하세요.";
        return "사이트 주소와 Cloud ID, 해당 계정의 Jira 접근 권한을 확인하세요.";
    }
    static void projectKey(String project) { MeetingService.require(project!=null&&project.matches("[A-Z][A-Z0-9_]{0,19}"),"올바른 Jira 프로젝트 키가 필요합니다."); }
    @Override public Map<String,Object> test() {
        var me=request("GET","/myself",null,false);
        return Map.of("mode","cloud","connected",true,"message","실제 Jira Cloud 연결 성공: "+me.path("displayName").asText("사용자"),"siteUrl",config.siteUrl());
    }
    List<JsonNode> pages(String path,String values) {
        var result=new ArrayList<JsonNode>(); int start=0;
        for(int page=0;page<100;page++) {
            var node=request("GET",path+(path.contains("?")?"&":"?")+"startAt="+start+"&maxResults=50",null,false);
            var entries=node.path(values); MeetingService.require(entries.isArray(),"Jira 메타데이터 응답 형식이 잘못되었습니다.");
            entries.forEach(result::add); start+=entries.size();
            if(entries.isEmpty()||node.path("isLast").asBoolean()||start>=node.path("total").asInt(Integer.MAX_VALUE))return result;
        }
        throw new ReadFailure("Jira 메타데이터가 너무 많습니다. 프로젝트 범위를 좁혀 주세요.");
    }
    @Override public List<Map<String,String>> projects() {
        return pages("/project/search","values").stream().map(n->Map.of("key",n.path("key").asText(),"name",n.path("name").asText())).toList();
    }
    @Override public JiraUserPage assignableUsers(String project,String query,String accountId,int startAt){
        projectKey(project);MeetingService.require(startAt>=0&&startAt<=10000&&Objects.toString(query,"").length()<=200&&Objects.toString(accountId,"").length()<=200,"담당자 검색 조건을 확인하세요.");
        String path="/user/assignable/search?project="+encode(project)+"&startAt="+startAt+"&maxResults=51";
        if(accountId!=null&&!accountId.isBlank())path+="&accountId="+encode(accountId);else if(query!=null&&!query.isBlank())path+="&query="+encode(query);
        var rows=request("GET",path,null,false);MeetingService.require(rows.isArray(),"Jira 담당자 목록을 읽을 수 없습니다.");
        var users=new ArrayList<JiraUser>();int count=Math.min(rows.size(),50);
        for(int i=0;i<count;i++){var n=rows.get(i);if(n.path("active").asBoolean(true)&&!n.path("accountId").asText().isBlank())users.add(new JiraUser(n.path("accountId").asText(),n.path("displayName").asText("사용자"),true));}
        return new JiraUserPage(users,rows.size()>50,startAt+count);
    }
    @Override public List<Map<String,String>> issueTypes(String project) {
        projectKey(project);
        return pages("/issue/createmeta/"+project+"/issuetypes","issueTypes").stream()
            .map(n->Map.of("id",n.path("id").asText(),"name",n.path("name").asText(),"subtask",n.path("subtask").asText("false"),"hierarchyLevel",n.path("hierarchyLevel").asText(""))).toList();
    }
    @Override public List<Map<String,Object>> fields(String project,String type) {
        projectKey(project); MeetingService.require(type.matches("[0-9]+"),"실제 Jira 이슈 유형 ID를 선택하세요.");
        return pages("/issue/createmeta/"+project+"/issuetypes/"+type,"fields").stream().map(n->{
            Map<String,Object> field=new LinkedHashMap<>();field.put("id",n.path("fieldId").asText());field.put("name",n.path("name").asText());
            field.put("required",n.path("required").asBoolean());field.put("hasDefaultValue",n.path("hasDefaultValue").asBoolean());
            field.put("customSchema",n.path("schema").path("custom").asText());
            field.put("schemaType",n.path("schema").path("type").asText());
            field.put("allowedValues",json.convertValue(n.path("allowedValues").isArray()?n.path("allowedValues"):json.createArrayNode(),List.class));return field;
        }).toList();
    }
    String resolveType(Draft draft) {
        if(!Objects.toString(draft.parentDraftId,"").isBlank()){
            MeetingService.require(!draft.parent.isBlank(),"하위 티켓에 연결할 부모 Jira 이슈가 없습니다.");
            var types=issueTypes(draft.projectKey).stream().filter(t->Boolean.parseBoolean(t.get("subtask"))).toList();
            if(draft.issueType.equals("Sub-task"))return types.stream().map(t->t.get("id")).findFirst().orElseThrow(()->new IllegalArgumentException("프로젝트에 하위 작업 유형이 없습니다. Jira에서 하위 작업을 활성화하세요."));
            return types.stream().filter(t->t.get("id").equals(draft.issueType)||t.get("name").equalsIgnoreCase(draft.issueType)).map(t->t.get("id")).findFirst().orElseThrow(()->new IllegalArgumentException("하위 티켓은 프로젝트의 하위 작업 유형을 선택해야 합니다."));
        }
        var types=issueTypes(draft.projectKey);
        if(draft.issueType.equals("Epic"))return types.stream().filter(t->!Boolean.parseBoolean(t.get("subtask"))&&(t.get("hierarchyLevel").equals("1")||t.get("name").equalsIgnoreCase("Epic")||t.get("name").equals("에픽"))).map(t->t.get("id")).findFirst().orElseThrow(()->new IllegalArgumentException("이 프로젝트에서 에픽 유형을 찾을 수 없습니다. 에픽을 활성화한 뒤 다시 등록하세요."));
        if(draft.issueType.equals("Task")){
            var task=types.stream().filter(t->!Boolean.parseBoolean(t.get("subtask"))&&(t.get("name").equalsIgnoreCase("Task")||t.get("name").equals("작업"))).findFirst();
            if(task.isPresent())return task.get().get("id");
        }
        return types.stream().filter(t->t.get("id").equals(draft.issueType)||t.get("name").equalsIgnoreCase(draft.issueType))
            .map(t->t.get("id")).findFirst().orElseThrow(()->new IllegalArgumentException("프로젝트에서 실제 사용 가능한 이슈 유형을 선택하고 다시 승인하세요."));
    }
    public static Map<String,Object> adf(String text) {
        var paragraphs=Arrays.stream(text.split("\\R",-1)).map(line->line.isEmpty()
            ? Map.<String,Object>of("type","paragraph","content",List.of())
            : Map.<String,Object>of("type","paragraph","content",List.of(Map.of("type","text","text",line)))).toList();
        return Map.of("type","doc","version",1,"content",paragraphs);
    }
    static String marker(Draft draft) { return "meetinghub-"+draft.id; }
    @Override public Optional<Result> find(Draft draft) {
        projectKey(draft.projectKey);
        // Draft IDs are server generated; refuse JQL control characters even on corrupted data.
        MeetingService.require(draft.id.matches("[a-zA-Z0-9-]{1,80}"),"티켓 식별자 형식이 잘못되었습니다.");
        var node=request("GET","/search/jql?jql="+encode("project = "+draft.projectKey+" AND labels = \""+marker(draft)+"\"")+"&fields=key&maxResults=2",null,false);
        var issues=node.path("issues"); MeetingService.require(issues.isArray(),"Jira 검색 응답을 읽을 수 없습니다.");
        MeetingService.require(issues.size()<=1,"동일 식별자의 Jira 이슈가 여러 개입니다. Jira에서 확인하세요.");
        if(issues.isEmpty())return Optional.empty();
        return Optional.of(readyForBoard(issue(draft,issues.get(0))));
    }
    // Reconciliation uses this same path, so a transition failure never creates a second issue.
    Result readyForBoard(Result result) {
        String path="/issue/"+result.issueKey();
        try {
            var status=request("GET",path+"?fields=status",null,false).path("fields").path("status");
            if(todoName(status.path("name").asText()))return result;
            var transitions=request("GET",path+"/transitions",null,false).path("transitions");
            MeetingService.require(transitions.isArray(),"Jira 상태 전환 목록을 확인할 수 없습니다.");
            var exact=new ArrayList<JsonNode>();var pending=new ArrayList<JsonNode>();
            for(var t:transitions)if(!t.path("id").asText().isBlank()&&t.path("isAvailable").asBoolean(true)) {
                if(todoName(t.path("to").path("name").asText()))exact.add(t);
                else if("new".equals(t.path("to").path("statusCategory").path("key").asText()))pending.add(t);
            }
            if(exact.isEmpty()&&"new".equals(status.path("statusCategory").path("key").asText()))return result;
            var candidates=exact.isEmpty()?pending:exact;
            MeetingService.require(candidates.size()==1,"해야 할 일 상태로 전환할 경로를 특정할 수 없습니다. Jira 워크플로와 상태 전환 권한을 확인하세요.");
            var target=candidates.get(0);
            request("POST",path+"/transitions",Map.of("transition",Map.of("id",target.path("id").asText())),false);
            var actual=request("GET",path+"?fields=status",null,false).path("fields").path("status");
            MeetingService.require(!actual.path("id").asText().isBlank()&&actual.path("id").asText().equals(target.path("to").path("id").asText()),"Jira 상태 전환 결과를 확인할 수 없습니다.");
            return result;
        } catch(RuntimeException e) {
            throw new Uncertain(result.issueKey()+" 이슈는 생성되었지만 해야 할 일 상태를 확인하지 못했습니다. "+e.getMessage()+" 결과 확인 시 기존 이슈로 재시도합니다.");
        }
    }
    static boolean todoName(String name){return Set.of("todo","해야할일","할일").contains(name.replaceAll("[\\s_-]","").toLowerCase(Locale.ROOT));}
    Result issue(Draft draft,JsonNode node) {
        String key=node.path("key").asText();
        if(!key.matches("[A-Z][A-Z0-9_]*-[0-9]+"))throw new Uncertain();
        return new Result(draft.id,"PUBLISHED",key,config.siteUrl()+"/browse/"+key,null);
    }
    @Override public Result create(Draft draft) {
        projectKey(draft.projectKey);
        // Read-before-create helps recover previously created issues; UNKNOWN is never automatically recreated.
        var previous=find(draft);if(previous.isPresent())return previous.get();
        String type=resolveType(draft);var metadata=fields(draft.projectKey,type);
        var byId=new HashMap<String,Map<String,Object>>();metadata.forEach(f->byId.put((String)f.get("id"),f));
        MeetingService.require(byId.containsKey("labels"),"생성 화면에 labels 필드를 추가하세요. 중복 방지 식별자를 저장하는 데 필요합니다.");
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("project",Map.of("key",draft.projectKey));fields.put("issuetype",Map.of("id",type));fields.put("summary",TicketWording.normalize(draft.summary));
        MeetingService.require(byId.containsKey("description"),"Jira 생성 화면에 description 필드가 필요합니다.");
        ScheduleDetails.ready(draft);fields.put("description",adf(TicketWording.normalize(ScheduleDetails.description(draft))));
        var labels=new LinkedHashSet<>(draft.labels);labels.add(marker(draft));labels.add("meetinghub");fields.put("labels",labels);
        optional(fields,byId,"assignee",draft.assignee.isBlank()?null:Map.of("accountId",draft.assignee));
        if(byId.containsKey("duedate")||!ScheduleDetails.present(draft))optional(fields,byId,"duedate",draft.dueDate.isBlank()?null:draft.dueDate);
        if(ScheduleDetails.present(draft)){
            if(byId.containsKey("timetracking")&&draft.estimatedMinutes!=null&&draft.estimatedMinutes>0)fields.put("timetracking",Map.of("originalEstimate",draft.estimatedMinutes+"m"));
            for(var field:metadata){
                String id=Objects.toString(field.get("id"),""),name=Objects.toString(field.get("name"),"").replaceAll("[\\s_-]","").toLowerCase(Locale.ROOT),schema=Objects.toString(field.get("schemaType"),"");
                boolean start=Set.of("startdate","starttime","시작일","시작일시","시작시간").contains(name),end=Set.of("enddate","endtime","종료일","종료일시","종료시간").contains(name);
                if(id.startsWith("customfield_")&&(start||end)&&Set.of("date","datetime").contains(schema)){
                    var value=java.time.OffsetDateTime.parse(start?draft.startAt:draft.endAt);fields.put(id,schema.equals("date")?value.toLocalDate().toString():value.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxx")));
                }
            }
        }
        optional(fields,byId,"parent",draft.parent.isBlank()?null:Map.of("key",draft.parent));
        if(draft.issueType.equals("Epic"))metadata.stream().filter(f->"com.pyxis.greenhopper.jira:gh-epic-label".equals(f.get("customSchema"))).forEach(f->fields.put((String)f.get("id"),draft.summary));
        var reporter=byId.get("reporter");
        if(reporter!=null&&Boolean.TRUE.equals(reporter.get("required"))&&!Boolean.TRUE.equals(reporter.get("hasDefaultValue"))) {
            String account=request("GET","/myself",null,false).path("accountId").asText();
            MeetingService.require(!account.isBlank(),"등록자 Jira accountId를 확인할 수 없습니다.");
            fields.put("reporter",Map.of("accountId",account));
        }
        if(byId.containsKey("priority")&&!draft.priority.isBlank()) {
            @SuppressWarnings("unchecked") var allowed=(List<Map<String,Object>>)byId.get("priority").get("allowedValues");
            var priority=(allowed==null?List.<Map<String,Object>>of():allowed).stream().filter(p->draft.priority.equals(p.get("id"))||draft.priority.equalsIgnoreCase(Objects.toString(p.get("name"),""))).findFirst();
            if(allowed!=null&&!allowed.isEmpty()) {
            MeetingService.require(priority.isPresent(),"프로젝트에서 사용 가능한 우선순위를 선택하세요.");
            fields.put("priority",Map.of("id",priority.orElseThrow().get("id")));
            }
        }
        for(var field:metadata)if(Boolean.TRUE.equals(field.get("required"))&&!Boolean.TRUE.equals(field.get("hasDefaultValue")))
            MeetingService.require(fields.containsKey(field.get("id")),"추가 필수 필드: "+field.get("name")+" ("+field.get("id")+"). 테스트 프로젝트의 필수 설정을 조정하세요.");
        return readyForBoard(issue(draft,request("POST","/issue",Map.of("fields",fields),true)));
    }
    @Override public Result linkExisting(Draft draft){
        MeetingService.require(draft.existingIssueKey!=null&&draft.existingIssueKey.matches("[A-Z][A-Z0-9_]*-[0-9]+")&&draft.existingIssueKey.length()<=100,"연결할 Jira 이슈 키 형식이 잘못되었습니다.");
        var node=request("GET","/issue/"+draft.existingIssueKey+"?fields=project,issuetype,parent",null,false);
        var fields=node.path("fields");
        MeetingService.require(fields.path("project").path("key").asText().equals(draft.projectKey),"기존 이슈의 프로젝트와 초안의 프로젝트가 다릅니다. 프로젝트를 맞춘 뒤 다시 승인하세요.");
        boolean subtask=fields.path("issuetype").path("subtask").asBoolean();
        if(!draft.parent.isBlank())MeetingService.require((Objects.toString(draft.parentDraftId,"").isBlank()&&draft.parent.equals(draft.epicKey)?!subtask:subtask)&&fields.path("parent").path("key").asText().equals(draft.parent),"기존 이슈의 부모가 선택한 부모 티켓과 다릅니다.");
        else MeetingService.require(!subtask,"기존 하위 이슈를 연결하려면 부모 티켓도 지정하세요.");
        return issue(draft,node);
    }
    static void optional(Map<String,Object> values,Map<String,Map<String,Object>> metadata,String key,Object value) {
        if(value==null)return;MeetingService.require(metadata.containsKey(key),"Jira 생성 화면에서 지원하지 않는 필드: "+key);values.put(key,value);
    }
}
