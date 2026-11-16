package kr.meetinghub;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/settings/ai/codex/login")
public class CodexLoginApi {
    final CodexLogin login;
    CodexLoginApi(CodexLogin login){this.login=login;}
    private ResponseEntity<Map<String,Object>> reply(Map<String,Object> value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @GetMapping public ResponseEntity<Map<String,Object>> status(){return reply(login.status());}
    @PostMapping public ResponseEntity<Map<String,Object>> start(){return reply(login.start());}
    @DeleteMapping public ResponseEntity<Map<String,Object>> cancel(){return reply(login.cancel());}
}
