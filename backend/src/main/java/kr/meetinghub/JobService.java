package kr.meetinghub;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import kr.meetinghub.Domain.*;

@Service
public class JobService {
    final MeetingService service; final Jobs jobs; final TransactionTemplate tx;
    final ThreadPoolTaskExecutor executor; final AiJobProcessor processor;
    JobService(MeetingService service,Jobs jobs,TransactionTemplate tx,ThreadPoolTaskExecutor executor,AiJobProcessor processor) {
        this.service=service; this.jobs=jobs; this.tx=tx; this.executor=executor; this.processor=processor;
    }
    public Job start(String meetingId,String type) { return start(meetingId,type,null); }
    public Job schedule(String meetingId,ScheduleRequest input){SchedulePlanner.validate(input);try{return enqueue(meetingId,"SCHEDULE",null,service.mapper().writeValueAsString(input));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("일정 설정을 저장할 수 없습니다.");}}
    public Job review(String draftId) {
        var draft=service.drafts().findById(draftId).orElseThrow(MeetingService::missing);
        return start(draft.meetingId,"REVIEW",draftId);
    }
    Job start(String meetingId,String type,String targetDraftId) {
        return enqueue(meetingId,type,targetDraftId,null);
    }
    Job enqueue(String meetingId,String type,String targetDraftId,String payload) {
        Job job=tx.execute(s->{
            var m=service.lock(meetingId); service.editable(m);
            MeetingService.require(jobs.findByMeetingIdOrderByCreatedAtDesc(meetingId).stream().noneMatch(j->j.status.equals("WAITING_CONFIRMATION")),"확인 대기 중인 작업을 계속하거나 취소한 뒤 새 분석을 시작하세요.");
            var j=new Job(); j.meetingId=meetingId; j.type=type; j.targetDraftId=targetDraftId;j.requestPayload=payload; jobs.save(j);
            j.advanceMode=type.equals("SCHEDULE")?"AUTO":service.content(m).analysisAdvanceMode;jobs.save(j);
            m.status="QUEUED"; service.meetings().save(m); return j;
        });
        try { executor.execute(()->run(job.id)); }
        catch(RuntimeException e) { fail(job.id,"작업 대기열이 가득 찼습니다. 재시도하세요."); }
        return jobs.findById(job.id).orElseThrow();
    }
    void run(String id) { processor.run(id); }
    public MeetingView setAdvanceMode(String meetingId,String mode){
        MeetingService.require(Set.of("AUTO","CONFIRM").contains(mode),"분석 진행 방식을 확인하세요.");
        return tx.execute(s->{
            var pending=jobs.findByMeetingIdOrderByCreatedAtDesc(meetingId).stream().filter(j->j.status.equals("WAITING_CONFIRMATION")).map(j->jobs.locked(j.id).orElseThrow()).toList();
            var m=service.lock(meetingId);service.editable(m);var c=service.content(m);c.analysisAdvanceMode=mode;service.save(m,c);
            for(var j:pending)if(j.status.equals("WAITING_CONFIRMATION")){j.advanceMode=mode;jobs.save(j);}
            return service.get(meetingId);
        });
    }
    public Job continueJob(String id,int expectedStageIndex){
        Job next=tx.execute(s->{
            var j=jobs.locked(id).orElseThrow(MeetingService::missing);
            MeetingService.require(j.status.equals("WAITING_CONFIRMATION"),"단계 확인 대기 중인 작업만 계속할 수 있습니다.");
            MeetingService.require(j.nextStageIndex==expectedStageIndex,"이미 다른 단계로 진행되었습니다. 최신 결과를 확인하세요.");
            var m=service.lock(j.meetingId);service.editable(m);var stages=AiJobProcessor.stages(j.type);
            MeetingService.require(j.nextStageIndex>0&&j.nextStageIndex<stages.size(),"다음 분석 단계가 없습니다.");
            if(stages.get(j.nextStageIndex).equals("GENERATING_TICKETS"))MeetingService.require(service.content(m).transcriptRevision==j.pausedTranscriptRevision,"업무 분석 후 음성 기록이 변경되었습니다. 현재 작업을 취소하고 업무 분석을 다시 실행하세요.");
            j.status="QUEUED";j.errorMessage=null;jobs.save(j);m.status="QUEUED";service.meetings().save(m);return j;
        });
        try{executor.execute(()->run(id));}catch(RuntimeException e){fail(id,"작업 대기열이 가득 찼습니다. 재시도하세요.");}
        return jobs.findById(next.id).orElseThrow();
    }
    void fail(String id,String message) {
        tx.executeWithoutResult(s->{
            var j=jobs.locked(id).orElseThrow(); if(j.status.equals("CANCELLED")) return;
            j.status="FAILED"; j.errorMessage=message; jobs.save(j);
            var m=service.lock(j.meetingId); m.status="FAILED"; service.meetings().save(m);
        });
    }
    public Job cancel(String id) {
        return tx.execute(s->{
            var j=jobs.locked(id).orElseThrow(MeetingService::missing);
            MeetingService.require(Set.of("QUEUED","RUNNING","WAITING_CONFIRMATION").contains(j.status),"진행 중이거나 확인 대기 중인 작업만 취소할 수 있습니다.");
            j.status="CANCELLED"; jobs.save(j); var m=service.lock(j.meetingId); m.status="CANCELLED"; service.meetings().save(m);
            service.audits().save(new Audit("JOB_CANCELLED",id)); return j;
        });
    }
}
