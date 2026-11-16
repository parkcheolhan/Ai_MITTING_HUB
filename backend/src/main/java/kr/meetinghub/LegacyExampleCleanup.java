package kr.meetinghub;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.meetinghub.Domain.*;

/** One-time removal of old shipped fixtures and known browser-test records. */
@Service
public class LegacyExampleCleanup {
    static final String MIGRATION="LEGACY_EXAMPLES_REMOVED_V1";
    final MeetingService service;
    LegacyExampleCleanup(MeetingService service){this.service=service;}
    @Transactional public void run(){
        if(service.audits().existsByAction(MIGRATION))return;
        for(var m:service.meetings().findAll()){
            var c=service.content(m);if(!isExample(m,c))continue;
            var drafts=service.drafts().findByMeetingId(m.id);
            var jobs=service.jobs().findByMeetingIdOrderByCreatedAtDesc(m.id);
            Set<String> targets=drafts.stream().map(d->d.id).collect(Collectors.toSet());targets.add(m.id);
            jobs.forEach(j->targets.add(j.id));
            service.audits().deleteAll(service.audits().findAll().stream().filter(a->targets.contains(a.targetId)).toList());
            service.jobs().deleteAll(jobs);
            service.drafts().deleteAll(drafts);service.meetings().delete(m);
        }
        service.audits().save(new Audit(MIGRATION,"system"));
    }
    static boolean isExample(Meeting m,Content c){
        // Do not classify user meetings by their contents alone, or by generic words such as "test".
        if(m.createdAt.compareTo("2026-10-25T04:30:00Z")>=0||!c.assets.isEmpty())return false;
        String expected;
        if(m.title.equals("[샘플] 제품 출시 준비 회의")&&c.memo.equals("시연용 가상 회의입니다."))expected="35b06996f3d709143c9756ee136fb14bc9c9d99fd44f1ffde36883b244339d9c";
        else if(m.title.matches("E2E 회의 [0-9]{13}"))expected="40553a8b399eac1298871f027001f0779d63fe464fdea34acb39166d84797afe";
        else if(m.title.matches("클로바 전사문 E2E [0-9]{13}"))expected="c719a0612b7dee7022ca7da0d51521c2a201c2d24365103ba463a206704aeff5";
        else if(m.title.equals("AI UI 원문 검증"))expected="88b890f31c8762344b890cb460732f7074e6833d65e6274aed9c08075d61755d";
        else return false;
        try{
            String text=c.segments.stream().map(Segment::text).collect(Collectors.joining("\n"));
            return expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
