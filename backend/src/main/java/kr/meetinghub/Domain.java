package kr.meetinghub;

import java.util.*;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

public final class Domain {
    private Domain() {}
    public record CreateMeeting(@NotBlank @Size(max=200) String title, String occurredAt,
        List<String> participants, @Pattern(regexp="[A-Z][A-Z0-9_]{0,19}") String projectKey,
        String memo, @Size(max=200000) String transcript, String source, String language,
        boolean diarization, @Size(max=20000) String template) {}
    public record Segment(String id, String speaker, long startMs, long endMs, String text,
                          boolean estimatedTime) {}
    public record TranscriptTiming(@Min(0) @Max(86400000) long startMs, @Min(1000) @Max(14400000) long intervalMs) {}
    public record MinutesTemplateInput(@NotBlank @Size(max=20000) String template) {}
    public record ParticipantsInput(@NotNull @Size(max=100) List<@NotBlank @Size(max=100) String> participants,@NotNull @Size(max=3000) Map<String,String> segmentSpeakers) {}
    public record Evidence(String segmentId, long startMs, String speaker, String quote,
                           boolean estimatedTime) {}
    public record Item(String id, String kind, String title, List<String> tasks,
        List<String> checks, List<String> acceptanceCriteria, List<String> dependencies,
        String assigneeSuggestion, String dueDateSuggestion, List<Evidence> evidence,
        double confidence, boolean inferred) {}
    public record TicketProposal(List<String> analysisItemIds,String summary,String background,
        List<String> tasks,List<String> checks,List<String> acceptanceCriteria,
        List<String> dependencies,List<String> reviewNotes,List<TicketProposal> subtasks,boolean aiRecommended,List<String> taskTitles,List<String> checkTitles,List<List<String>> taskReviews,List<List<String>> checkReviews) {
        public TicketProposal {subtasks=subtasks==null?List.of():List.copyOf(subtasks);taskTitles=taskTitles==null?List.of():List.copyOf(taskTitles);checkTitles=checkTitles==null?List.of():List.copyOf(checkTitles);taskReviews=taskReviews==null?List.of():taskReviews.stream().map(List::copyOf).toList();checkReviews=checkReviews==null?List.of():checkReviews.stream().map(List::copyOf).toList();}
        public TicketProposal(List<String> ids,String summary,String background,List<String> tasks,List<String> checks,List<String> criteria,List<String> dependencies,List<String> notes,List<TicketProposal> subtasks,boolean recommended,List<String> taskTitles,List<String> checkTitles){this(ids,summary,background,tasks,checks,criteria,dependencies,notes,subtasks,recommended,taskTitles,checkTitles,List.of(),List.of());}
        public TicketProposal(List<String> ids,String summary,String background,List<String> tasks,List<String> checks,List<String> criteria,List<String> dependencies,List<String> notes,List<TicketProposal> subtasks,boolean recommended){this(ids,summary,background,tasks,checks,criteria,dependencies,notes,subtasks,recommended,List.of(),List.of());}
        public TicketProposal(List<String> ids,String summary,String background,List<String> tasks,List<String> checks,List<String> criteria,List<String> dependencies,List<String> notes,List<TicketProposal> subtasks){this(ids,summary,background,tasks,checks,criteria,dependencies,notes,subtasks,false);}
        public TicketProposal(List<String> ids,String summary,String background,List<String> tasks,List<String> checks,List<String> criteria,List<String> dependencies,List<String> notes){this(ids,summary,background,tasks,checks,criteria,dependencies,notes,List.of());}
    }
    public static class Minutes {
        public String formatted = "";
        public int version = 1;
        public String overview = "", summary = "", discussion = "", decisions = "", openQuestions = "", followUp = "";
        public String generationMode="mock",generationModel="rules";
    }
    public static class Content {
        public String occurredAt = "", projectKey = "DEMO", memo = "", source = "TEXT", language = "ko", template = "일반";
        public boolean diarization;
        public List<String> participants = new ArrayList<>();
        public List<Segment> segments = new ArrayList<>();
        public Map<String,String> speakerGroups = new LinkedHashMap<>();
        public Minutes minutes;
        public List<Item> items = new ArrayList<>();
        public List<Item> archivedItems = new ArrayList<>();
        public String analysisMode="mock",analysisModel="rules";
        public int analysisVersion;
        public List<Asset> assets = new ArrayList<>();
        public ScheduleRequest scheduleSettings;
    }
    public record Asset(String id, String originalName, String mediaType, long size, String storageKey) {}
    public record MeetingView(String id, String title, String status, String createdAt,
        Content content, List<Draft> drafts, List<Job> jobs,List<EpicView> epics) {}
    public record EpicView(String id,String projectKey,String summary,String status,String issueKey,String issueUrl,String error,String contentStatus,String contentError){}
    public record DraftInput(@NotBlank @Size(max=20) String projectKey, @NotBlank String issueType,
        @NotBlank @Size(max=255) String summary, @NotBlank @Size(max=30000) String description,
        String assignee, String priority, String dueDate, List<String> labels, String parent,
        List<String> analysisItemIds,String parentDraftId,String registrationMode,String existingIssueKey,
        String assigneeName,String startAt,String endAt,Integer estimatedMinutes,String scheduleMode,List<String> dependencyDraftIds) {
        public DraftInput(String projectKey,String issueType,String summary,String description,String assignee,String priority,String dueDate,List<String> labels,String parent,List<String> analysisItemIds,String parentDraftId,String registrationMode,String existingIssueKey){this(projectKey,issueType,summary,description,assignee,priority,dueDate,labels,parent,analysisItemIds,parentDraftId,registrationMode,existingIssueKey,null,null,null,null,"MANUAL",null);}
        public DraftInput(String projectKey,String issueType,String summary,String description,String assignee,String priority,String dueDate,List<String> labels,String parent,List<String> analysisItemIds,String parentDraftId){this(projectKey,issueType,summary,description,assignee,priority,dueDate,labels,parent,analysisItemIds,parentDraftId,null,null);}
        public DraftInput(String projectKey,String issueType,String summary,String description,String assignee,String priority,String dueDate,List<String> labels,String parent,List<String> analysisItemIds){this(projectKey,issueType,summary,description,assignee,priority,dueDate,labels,parent,analysisItemIds,null);}
    }
    public record Ids(@NotEmpty List<String> ids) {}
    public record CompactGroup(@NotEmpty @Size(max=200) List<String> ids,@NotBlank @Size(max=200) String summary) {}
    public record CompactPlan(@NotEmpty @Size(max=200) List<@Valid CompactGroup> groups,@NotEmpty @Size(max=1000) Map<String,Long> versions) {}
    public record JiraUser(String accountId,String displayName,boolean active) {}
    public record JiraUserPage(List<JiraUser> users,boolean hasMore,int nextStartAt) {}
    public record AssignChildren(@NotBlank String assignee,String assigneeName) {}
    public record ApplyChildSchedule(@NotBlank @Pattern(regexp="MANUAL|AI") String scheduleMode,String startAt,String endAt,String dueDate) {}
    public record Availability(@NotBlank String assignee,String availableAt,@Min(0) @Max(23) int workStartHour,@Min(1) @Max(24) int workEndHour) {}
    public record ScheduleRequest(@NotBlank String startAt,@NotBlank String timezone,@Min(0) @Max(23) int workStartHour,@Min(1) @Max(24) int workEndHour,List<@Valid Availability> availability) {}
    public record ScheduleTask(String id,String parentTitle,String summary,String description,String assignee,String assigneeName,String currentPriority,boolean fixed,String startAt,String endAt,List<String> dependencyDraftIds) {}
    public record SchedulePriority(String id,String name) {}
    public record ScheduleAssessment(String draftId,int estimatedMinutes,String priority,List<String> dependencyDraftIds,String reason) {}
    public record Result(String draftId, String status, String issueKey, String issueUrl, String error) {}
    @Entity(name="Meeting") @Table(name="meetings")
    public static class Meeting {
        @Id public String id = UUID.randomUUID().toString();
        public String title;
        public String status = "UPLOADED";
        public String createdAt = java.time.Instant.now().toString();
        @Column(columnDefinition="text", nullable=false) public String payload = "{}";
        @Version public long version;
    }
    @Entity(name="Draft") @Table(name="ticket_drafts")
    public static class Draft {
        @Id public String id = UUID.randomUUID().toString();
        @Column(nullable=false) public String meetingId;
        public String projectKey = "DEMO", issueType = "Task", summary = "";
        @Column(columnDefinition="text") public String description = "";
        public String assignee = "", priority = "Medium", dueDate = "", parent = "";
        public String assigneeName="", startAt="", endAt="", scheduleMode="AI";
        public Integer estimatedMinutes=0;
        @Column(columnDefinition="text") public String scheduleReason="";
        @ElementCollection(fetch=FetchType.EAGER) public Set<String> dependencyDraftIds=new LinkedHashSet<>();
        public String parentDraftId="";
        public String epicKey="";
        public String registrationMode="NEW",existingIssueKey="";
        @ElementCollection(fetch=FetchType.EAGER) public Set<String> labels = new LinkedHashSet<>();
        @ElementCollection(fetch=FetchType.EAGER) public Set<String> analysisItemIds = new LinkedHashSet<>();
        public String status = "DRAFT", issueKey, issueUrl, publishedAt;
        public String publishTarget;
        public String approvedTarget;
        public String generationMode="manual",generationModel;
        public Integer analysisVersion=0;
        public Integer groupingVersion=0;
        public String consolidatedInto="";
        @com.fasterxml.jackson.annotation.JsonIgnore @Column(columnDefinition="text") public String consolidationBackup="";
        @Column(length=2000) public String error;
        public boolean duplicateSuspected;
        @Version public long version;
    }
    @Entity(name="Job") @Table(name="processing_jobs")
    public static class Job {
        @Id public String id = UUID.randomUUID().toString();
        public String meetingId, type = "PIPELINE", status = "QUEUED";
        public String stage = "";
        public String targetDraftId;
        @com.fasterxml.jackson.annotation.JsonIgnore @Column(columnDefinition="text") public String requestPayload;
        public int progress;
        public String errorMessage;
        public String createdAt = java.time.Instant.now().toString();
    }
    @Entity(name="Audit") @Table(name="audit_logs")
    public static class Audit {
        @Id public String id = UUID.randomUUID().toString();
        public String actorId = "local-demo-user", action, targetId;
        public String timestamp = java.time.Instant.now().toString();
        public Audit() {}
        public Audit(String action, String targetId) { this.action=action; this.targetId=targetId; }
    }
}
