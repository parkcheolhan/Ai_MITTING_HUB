package kr.meetinghub;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import kr.meetinghub.Domain.*;

interface Meetings extends JpaRepository<Meeting,String> {
    List<Meeting> findAllByOrderByCreatedAtDesc();
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select m from Meeting m where m.id=:id")
    Optional<Meeting> locked(@Param("id") String id);
}
interface Drafts extends JpaRepository<Draft,String> {
    @Query("select d from Draft d where d.parentDraftId=:parentDraftId and d.status<>'CONSOLIDATED'") List<Draft> findByParentDraftId(@Param("parentDraftId") String parentDraftId);
    @Query("select d from Draft d where d.meetingId=:meetingId and d.status<>'CONSOLIDATED'") List<Draft> findByMeetingId(@Param("meetingId") String meetingId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from Draft d where d.id=:id")
    Optional<Draft> locked(@Param("id") String id);
}
interface Jobs extends JpaRepository<Job,String> {
    List<Job> findByMeetingIdOrderByCreatedAtDesc(String meetingId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select j from Job j where j.id=:id")
    Optional<Job> locked(@Param("id") String id);
}
interface Audits extends JpaRepository<Audit,String> {
    boolean existsByAction(String action);
    List<Audit> findTop100ByOrderByTimestampDesc();
}
