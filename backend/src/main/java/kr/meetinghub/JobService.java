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
            var j=new Job(); j.meetingId=meetingId; j.type=type; j.targetDraftId=targetDraftId;j.requestPayload=payload; jobs.save(j);
            m.status="QUEUED"; service.meetings().save(m); return j;
        });
        try { executor.execute(()->run(job.id)); }
        catch(RuntimeException e) { fail(job.id,"작업 대기열이 가득 찼습니다. 재시도하세요."); }
        return jobs.findById(job.id).orElseThrow();
    }
    void run(String id) { processor.run(id); }
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
            MeetingService.require(Set.of("QUEUED","RUNNING").contains(j.status),"진행 중인 작업만 취소할 수 있습니다.");
            j.status="CANCELLED"; jobs.save(j); var m=service.lock(j.meetingId); m.status="CANCELLED"; service.meetings().save(m);
            service.audits().save(new Audit("JOB_CANCELLED",id)); return j;
        });
    }
}
