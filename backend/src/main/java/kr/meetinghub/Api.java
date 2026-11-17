package kr.meetinghub;

import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import kr.meetinghub.Domain.*;

@RestController @RequestMapping("/api")
public class Api {
    final MeetingService service; final JobService worker; final Publisher publisher; final Path uploadDir; final JiraIntegration integration;
    Api(MeetingService service,JobService worker,Publisher publisher,JiraIntegration integration,@Value("${app.upload-dir}") String uploadDir) {
        this.service=service; this.worker=worker; this.publisher=publisher; this.integration=integration; this.uploadDir=Path.of(uploadDir).toAbsolutePath().normalize();
    }
    @GetMapping("/meetings") public List<MeetingView> list() { return service.list(); }
    @PostMapping("/meetings") @ResponseStatus(HttpStatus.CREATED)
    public MeetingView create(@Valid @RequestBody CreateMeeting input) { return service.create(input); }
    @GetMapping("/meetings/{id}") public MeetingView get(@PathVariable String id) { return service.get(id); }
    @org.springframework.beans.factory.annotation.Autowired EpicBoard epicBoard;
    @PostMapping("/meetings/{id}/epics") public MeetingView addEpic(@PathVariable String id,@Valid @RequestBody AddEpic input){return epicBoard.add(id,input);}
    @PutMapping("/ticket-drafts/{id}/epic") public List<Draft> moveEpic(@PathVariable String id,@Valid @RequestBody MoveEpic input){return epicBoard.move(id,input);}
    @PostMapping("/meetings/{id}/epics/sync") public List<EpicView> syncEpics(@PathVariable String id){
        service.get(id);var jira=integration.jira.snapshot();
        service.epics().findByMeetingId(id).stream().filter(e->e.status.equals("PUBLISHED")&&e.target.equals(jira.target())).forEach(e->publisher.epicPublisher.contents.sync(e.id,jira));
        return service.get(id).epics();
    }
    @PostMapping("/meetings/{id}/assets") public Asset upload(@PathVariable String id,@RequestParam MultipartFile file) throws IOException {
        return storeAsset(id,file,false);
    }
    @PutMapping("/meetings/{id}/assets") public Asset replaceFailedSpeechAsset(@PathVariable String id,@RequestParam MultipartFile file)throws IOException{
        return storeAsset(id,file,true);
    }
    private Asset storeAsset(String id,MultipartFile file,boolean replace)throws IOException{
        service.get(id); MeetingService.require(!file.isEmpty()&&file.getSize()<=100*1024*1024L,"파일은 100MB 이하로 등록하세요.");
        String original=Objects.toString(file.getOriginalFilename(),"file");
        String extension=original.contains(".")?original.substring(original.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        var types=Map.of("mp3","audio/mpeg","wav","audio/wav","m4a","audio/mp4","aac","audio/aac","acc","audio/aac","mp4","video/mp4","webm","video/webm");
        MeetingService.require(types.containsKey(extension),"mp3, wav, m4a, aac, acc, mp4, webm 형식만 지원합니다.");
        String key=UUID.randomUUID()+"."+extension; Files.createDirectories(uploadDir);
        Path path=uploadDir.resolve(key).normalize(); MeetingService.require(path.startsWith(uploadDir),"잘못된 경로입니다.");
        try(var stream=file.getInputStream()) { Files.copy(stream,path); }
        var asset=new Asset(UUID.randomUUID().toString(),original,types.get(extension),file.getSize(),key);
        try { if(replace)service.replaceFailedSpeechAsset(id,asset);else service.addAsset(id,asset); } catch(RuntimeException e) { Files.deleteIfExists(path); throw e; }
        return asset;
    }
    @GetMapping("/meetings/{id}/assets/{assetId}") public ResponseEntity<Resource> media(@PathVariable String id,@PathVariable String assetId) throws IOException {
        var asset=service.get(id).content().assets.stream().filter(a->a.id().equals(assetId)).findFirst().orElseThrow(MeetingService::missing);
        Path path=uploadDir.resolve(asset.storageKey()).normalize(); MeetingService.require(path.startsWith(uploadDir),"잘못된 경로입니다.");
        var resource=new FileSystemResource(path); if(!resource.exists()) throw MeetingService.missing();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.mediaType())).contentLength(resource.contentLength()).body(resource);
    }
    @PostMapping("/meetings/{id}/process") public Job process(@PathVariable String id) { return worker.start(id,"PIPELINE"); }
    @PutMapping("/meetings/{id}/analysis-flow") public MeetingView flow(@PathVariable String id,@Valid @RequestBody AnalysisFlowInput input){return worker.setAdvanceMode(id,input.mode());}
    @PostMapping("/meetings/{id}/ticket-drafts/structure") public List<Draft> structure(@PathVariable String id){return service.structureCandidates(id);}
    @PostMapping("/meetings/{id}/ticket-drafts/format") public List<Draft> formatDescriptions(@PathVariable String id,@Valid @RequestBody Ids input){return service.formatDescriptions(id,input);}
    @PostMapping("/meetings/{id}/ticket-drafts/titles") public List<Draft> formatTitles(@PathVariable String id,@Valid @RequestBody Ids input){return service.formatSubtaskTitles(id,input);}
    @PostMapping("/meetings/{id}/ticket-drafts/consolidate") public List<Draft> consolidate(@PathVariable String id,@Valid @RequestBody Ids input){return service.consolidateSubtasks(id,input);}
    @org.springframework.beans.factory.annotation.Autowired TicketConsolidation consolidation;
    @PostMapping("/meetings/{id}/ticket-drafts/compact") public List<Draft> compact(@PathVariable String id,@Valid @RequestBody CompactPlan input){return consolidation.apply(id,input);}
    @PostMapping("/meetings/{id}/transcribe") public Job transcribe(@PathVariable String id) { return worker.start(id,"TRANSCRIBE"); }
    @GetMapping("/meetings/{id}/transcript") public List<Segment> transcript(@PathVariable String id) { return service.get(id).content().segments; }
    @PutMapping("/meetings/{id}/transcript") public MeetingView editTranscript(@PathVariable String id,@Valid @RequestBody TranscriptTextInput input){return service.editTranscript(id,input);}
    @PostMapping("/meetings/{id}/transcript/review") public Job reviewTranscript(@PathVariable String id){return worker.start(id,"TRANSCRIPT_REVIEW");}
    @PutMapping("/meetings/{id}/transcript/timing") public MeetingView timing(@PathVariable String id,@Valid @RequestBody TranscriptTiming input) { return service.setTranscriptTiming(id,input); }
    @PostMapping("/meetings/{id}/speakers/analyze") public Job speakers(@PathVariable String id){return worker.start(id,"SPEAKERS");}
    @PutMapping("/meetings/{id}/participants") public MeetingView participants(@PathVariable String id,@Valid @RequestBody ParticipantsInput input){return service.setParticipants(id,input);}
    @PostMapping("/meetings/{id}/minutes/generate") public Job minutes(@PathVariable String id) { return worker.start(id,"MINUTES"); }
    @PutMapping("/meetings/{id}/minutes/template") public MeetingView template(@PathVariable String id,@Valid @RequestBody MinutesTemplateInput input){return service.setMinutesTemplate(id,input);}
    @PutMapping("/meetings/{id}/minutes") public Minutes saveMinutes(@PathVariable String id,@RequestBody Minutes minutes) { return service.saveMinutes(id,minutes); }
    @PostMapping("/meetings/{id}/analyze") public Job analyze(@PathVariable String id) { return worker.start(id,"ANALYZE"); }
    @PostMapping("/meetings/{id}/schedule") @ResponseStatus(HttpStatus.ACCEPTED) public Job schedule(@PathVariable String id,@Valid @RequestBody ScheduleRequest input){return worker.schedule(id,input);}
    @PostMapping("/ticket-drafts/{id}/schedule/children") public List<Draft> applyChildSchedule(@PathVariable String id,@Valid @RequestBody ApplyChildSchedule input){return service.applyChildSchedule(id,input);}
    @PostMapping("/ticket-drafts/{id}/assignee/children") public List<Draft> assignChildren(@PathVariable String id,@Valid @RequestBody AssignChildren input){
        var draft=service.drafts().findById(id).orElseThrow(MeetingService::missing);var project=service.get(draft.meetingId).content().projectKey;
        var user=integration.jira.snapshot().assignableUsers(project,"",input.assignee(),0).users().stream().filter(u->u.accountId().equals(input.assignee())).findFirst().orElseThrow(()->new IllegalArgumentException("해당 프로젝트에 할당 가능한 담당자를 선택하세요."));
        return service.assignChildren(id,user);
    }
    @GetMapping("/meetings/{id}/items") public List<Item> items(@PathVariable String id) { return service.get(id).content().items; }
    @PostMapping("/meetings/{id}/ticket-drafts") @ResponseStatus(HttpStatus.ACCEPTED)
    public Job generate(@PathVariable String id) { return worker.start(id,"TICKETS"); }
    @PostMapping("/ticket-drafts/{id}/ai-review") @ResponseStatus(HttpStatus.ACCEPTED)
    public Job review(@PathVariable String id) { return worker.review(id); }
    @PostMapping("/meetings/{id}/ticket-drafts/manual") public Draft add(@PathVariable String id,@Valid @RequestBody DraftInput input) { return service.addDraft(id,input); }
    @GetMapping("/ticket-drafts") public List<Draft> drafts() { return service.drafts().findAll().stream().filter(d->!d.status.equals("CONSOLIDATED")).toList(); }
    @PutMapping("/ticket-drafts/{id}") public Draft edit(@PathVariable String id,@Valid @RequestBody DraftInput input) { return service.update(id,input); }
    @DeleteMapping("/ticket-drafts/{id}") public void delete(@PathVariable String id) { service.deleteDraft(id); }
    @PostMapping("/ticket-drafts/approve") public List<Draft> approve(@Valid @RequestBody Ids ids) { return service.approve(ids.ids()); }
    @PostMapping("/ticket-drafts/merge") public List<Draft> merge(@Valid @RequestBody Ids ids) { return service.merge(ids.ids()); }
    @PostMapping("/ticket-drafts/{id}/split") public List<Draft> split(@PathVariable String id) { return service.split(id); }
    @PostMapping("/ticket-drafts/publish") public List<Result> publish(@Valid @RequestBody Ids ids) {
        return publisher.publishBatch(ids.ids());
    }
    @GetMapping("/meetings/{id}/jira-results") public List<Draft> results(@PathVariable String id) { return service.get(id).drafts(); }
    @GetMapping("/jobs/{id}") public Job job(@PathVariable String id) { return service.jobs().findById(id).orElseThrow(MeetingService::missing); }
    @PostMapping("/jobs/{id}/cancel") public Job cancel(@PathVariable String id) { return worker.cancel(id); }
    @PostMapping("/jobs/{id}/continue") public Job continueJob(@PathVariable String id,@Valid @RequestBody ContinueStageInput input){return worker.continueJob(id,input.expectedStageIndex());}
    @PostMapping("/jobs/{id}/retry") public Job retry(@PathVariable String id) {
        var old=job(id); MeetingService.require(Set.of("FAILED","CANCELLED").contains(old.status),"실패 또는 취소된 작업만 재시도할 수 있습니다.");
        if(old.type.equals("SCHEDULE")){try{return worker.schedule(old.meetingId,service.mapper().readValue(old.requestPayload,ScheduleRequest.class));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("이전 일정 설정을 읽을 수 없습니다.");}}
        return worker.start(old.meetingId,old.type,old.targetDraftId);
    }
    @GetMapping("/settings") public ResponseEntity<Map<String,Object>> settings() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(integration.settings());
    }
    @PutMapping("/settings/jira") public ResponseEntity<Map<String,Object>> saveSettings(@Valid @RequestBody JiraSettingsStore.Input input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(integration.saveSettings(input));
    }
    @PutMapping("/settings/jira/project") public ResponseEntity<Map<String,Object>> saveProject(@Valid @RequestBody JiraSettingsStore.ProjectInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(integration.saveProject(input.projectKey()));
    }
    @DeleteMapping("/settings/jira/token") public ResponseEntity<Map<String,Object>> removeToken() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(integration.removeToken());
    }
    @PostMapping("/integrations/jira/test") public Map<String,Object> test() { return integration.jira.test(); }
    @GetMapping("/integrations/jira/projects") public List<Map<String,String>> projects() { return integration.jira.projects(); }
    @GetMapping("/integrations/jira/projects/{key}/epics") public JiraEpicPage epics(@PathVariable String key,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String nextPageToken){return integration.jira.epics(key,query,nextPageToken);}
    @GetMapping("/integrations/jira/projects/{key}/issue-types") public List<Map<String,String>> issueTypes(@PathVariable String key) { return integration.jira.issueTypes(key); }
    @GetMapping("/integrations/jira/projects/{key}/assignees") public JiraUserPage assignees(@PathVariable String key,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String accountId,@RequestParam(defaultValue="0") int startAt){return integration.jira.assignableUsers(key,query,accountId,startAt);}
    @GetMapping("/integrations/jira/projects/{key}/issue-types/{type}/fields") public List<Map<String,Object>> fields(@PathVariable String key,@PathVariable String type) { return integration.jira.fields(key,type); }
    public record TestIssue(@jakarta.validation.constraints.Pattern(regexp="[A-Z][A-Z0-9_]{0,19}") String projectKey,@jakarta.validation.constraints.NotBlank String issueType,boolean approved) {}
    @PostMapping("/integrations/jira/test-issue") public Result testIssue(@Valid @RequestBody TestIssue input) { return integration.testIssue(input.projectKey(),input.issueType(),input.approved()); }
    @GetMapping("/audit-logs") public List<Audit> audit() { return service.audits().findTop100ByOrderByTimestampDesc(); }
}
