package kr.meetinghub;

import java.util.*;
import java.time.Instant;
import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import kr.meetinghub.Domain.Audit;

@Service @Transactional
public class MinutesTemplateLibrary {
    public record Input(@NotBlank @Size(max=255) String name,@NotBlank @Size(max=20000) String template){}
    public record View(String id,String name,String template,String createdAt){}
    final MinutesTemplateRepository repository;final Audits audits;
    MinutesTemplateLibrary(MinutesTemplateRepository repository,Audits audits){this.repository=repository;this.audits=audits;}
    public List<View> list(){return repository.findAllByOrderByCreatedAtAsc().stream().map(this::view).toList();}
    public View create(Input input){
        String template=input.template().startsWith("\uFEFF")?input.template().substring(1):input.template();
        MeetingService.require(MinutesTemplate.custom(template),"업로드 양식에는 회의 내용 항목을 넣어주세요.");
        MinutesTemplate.validate(template);
        var existing=repository.findAllByOrderByCreatedAtAsc().stream().filter(t->t.template.equals(template)).findFirst();
        if(existing.isPresent())return view(existing.get());
        var entry=new SavedMinutesTemplate();entry.name=input.name().strip();entry.template=template;
        repository.save(entry);audits.save(new Audit("MINUTES_TEMPLATE_UPLOADED",entry.id));return view(entry);
    }
    View view(SavedMinutesTemplate entry){return new View(entry.id,entry.name,entry.template,entry.createdAt);}
}
@Entity @Table(name="minutes_template_library")
class SavedMinutesTemplate {
    @Id public String id=UUID.randomUUID().toString();
    @Column(nullable=false,length=255) public String name;
    @Column(name="template_text",nullable=false,columnDefinition="text") public String template;
    public String createdAt=Instant.now().toString();
}
interface MinutesTemplateRepository extends JpaRepository<SavedMinutesTemplate,String>{List<SavedMinutesTemplate> findAllByOrderByCreatedAtAsc();}
@RestController @RequestMapping("/api/minutes-templates")
class MinutesTemplateLibraryApi {
    final MinutesTemplateLibrary library;
    MinutesTemplateLibraryApi(MinutesTemplateLibrary library){this.library=library;}
    @GetMapping public List<MinutesTemplateLibrary.View> list(){return library.list();}
    @PostMapping public MinutesTemplateLibrary.View upload(@Valid @RequestBody MinutesTemplateLibrary.Input input){return library.create(input);}
}
