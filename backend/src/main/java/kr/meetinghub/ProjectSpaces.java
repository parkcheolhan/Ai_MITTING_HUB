package kr.meetinghub;

import java.time.Instant;
import java.util.*;
import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import kr.meetinghub.Domain.Audit;

@Service @Transactional
public class ProjectSpaces {
    public record Input(@NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_]{0,19}") String key,@NotBlank @Size(max=100) String name,@Size(max=2000) String description){}
    public record View(String key,String name,String description,String createdAt,long meetingCount){}
    final ProjectSpaceRepository repository;final MeetingService meetings;final Audits audits;
    ProjectSpaces(ProjectSpaceRepository repository,MeetingService meetings,Audits audits){this.repository=repository;this.meetings=meetings;this.audits=audits;}
    public List<View> list(){
        var counts=new HashMap<String,Long>();meetings.meetings().findAll().forEach(m->counts.merge(meetings.content(m).projectKey,1L,Long::sum));
        return repository.findAllByOrderByCreatedAtDesc().stream().map(p->new View(p.key,p.name,p.description,p.createdAt,counts.getOrDefault(p.key,0L))).toList();
    }
    public View create(Input input){
        String key=input.key().toUpperCase(Locale.ROOT);MeetingService.require(!input.name().strip().isEmpty(),"프로젝트 이름을 입력하세요.");
        if(repository.existsById(key))throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 등록된 프로젝트 키입니다.");
        var p=new ProjectSpace();p.key=key;p.name=input.name().strip();p.description=Objects.toString(input.description(),"").strip();
        try{repository.saveAndFlush(p);}catch(DataIntegrityViolationException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 등록된 프로젝트 키입니다.");}
        audits.save(new Audit("PROJECT_SPACE_CREATED",key));return view(p);
    }
    public View update(String key,Input input){
        MeetingService.require(key.equalsIgnoreCase(input.key()),"프로젝트 키는 변경할 수 없습니다.");
        var p=repository.findById(key.toUpperCase(Locale.ROOT)).orElseThrow(MeetingService::missing);
        MeetingService.require(!input.name().strip().isEmpty(),"프로젝트 이름을 입력하세요.");
        p.name=input.name().strip();p.description=Objects.toString(input.description(),"").strip();repository.save(p);
        audits.save(new Audit("PROJECT_SPACE_UPDATED",p.key));return view(p);
    }
    View view(ProjectSpace p){return new View(p.key,p.name,p.description,p.createdAt,meetings.meetings().findAll().stream().filter(m->p.key.equals(meetings.content(m).projectKey)).count());}
}
@Entity @Table(name="project_spaces")
class ProjectSpace {
    @Id @Column(name="project_key",length=20) public String key;
    @Column(nullable=false,length=100) public String name;
    @Column(length=2000) public String description="";
    public String createdAt=Instant.now().toString();
    @Version public long version;
}
interface ProjectSpaceRepository extends JpaRepository<ProjectSpace,String>{List<ProjectSpace> findAllByOrderByCreatedAtDesc();}
@RestController @RequestMapping("/api/projects")
class ProjectSpaceApi {
    final ProjectSpaces spaces;
    ProjectSpaceApi(ProjectSpaces spaces){this.spaces=spaces;}
    @GetMapping public List<ProjectSpaces.View> list(){return spaces.list();}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ProjectSpaces.View create(@Valid @RequestBody ProjectSpaces.Input input){return spaces.create(input);}
    @PutMapping("/{key}") public ProjectSpaces.View update(@PathVariable String key,@Valid @RequestBody ProjectSpaces.Input input){return spaces.update(key,input);}
}
