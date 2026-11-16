package kr.meetinghub;

import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class AiApi {
    final AiSettingsStore settings;final ConfiguredIntelligence ai;
    AiApi(AiSettingsStore settings,ConfiguredIntelligence ai){this.settings=settings;this.ai=ai;}
    @GetMapping("/settings/ai") public ResponseEntity<Map<String,Object>> get(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.view());}
    @PutMapping("/settings/ai") public ResponseEntity<Map<String,Object>> save(@Valid @RequestBody AiSettingsStore.Input input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.save(input));}
    @DeleteMapping("/settings/ai/key") public ResponseEntity<Map<String,Object>> remove(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.removeKey());}
    @PostMapping("/integrations/ai/test") public Map<String,Object> test(){return ai.test();}
}
