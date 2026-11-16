package kr.meetinghub;

import java.util.Objects;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix="ai")
public record AiConfig(String mode,String model,String apiKey) {
    public AiConfig {mode=Objects.toString(mode,"openai");model=Objects.toString(model,"gpt-4.1-mini");apiKey=Objects.toString(apiKey,"").strip();}
    @Override public String toString(){return "AiConfig[mode="+mode+", model="+model+", apiKey=REDACTED]";}
}
@Configuration @EnableConfigurationProperties(AiConfig.class)
class AiConfiguration {}
