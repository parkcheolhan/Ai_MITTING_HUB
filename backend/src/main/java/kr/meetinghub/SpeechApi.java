package kr.meetinghub;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/integrations/speech")
public class SpeechApi {
    final LocalSpeech speech;
    SpeechApi(LocalSpeech speech){this.speech=speech;}
    @GetMapping public Map<String,Object> status(){return speech.status();}
}
