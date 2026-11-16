package kr.meetinghub;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodexIntelligenceTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void allowsLongAnalysisAndCleansTemporaryFiles()throws Exception{
        var command=new ArrayList<String>();var directories=new ArrayList<Path>();
        var ai=new CodexIntelligence(new AiConfig("codex","codex-default",""),json,(args,dir)->{
            command.addAll(args);directories.add(dir);Files.writeString(Path.of(args.get(args.indexOf("--output-last-message")+1)),"{\"items\":[]}");
            return new CodexLoginTest.Child("").finish(0);
        },600);
        assertThat(ai.call("test","test",Map.of(),Map.of("type","object"),100).path("items").isArray()).isTrue();
        assertThat(command).contains("model_reasoning_effort=\"low\"");assertThat(directories.get(0)).doesNotExist();
        assertThat(CodexIntelligence.configuredTimeout(null)).isEqualTo(600);
        assertThat(CodexIntelligence.configuredTimeout("900")).isEqualTo(900);
        assertThatThrownBy(()->CodexIntelligence.configuredTimeout("0")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void timeoutKillsChildAndCleansTemporaryFiles(){
        var directories=new ArrayList<Path>();var child=new CodexLoginTest.Child(""){
            @Override public boolean waitFor(long timeout,TimeUnit unit){assertThat(timeout).isEqualTo(600);assertThat(unit).isEqualTo(TimeUnit.SECONDS);return false;}
        };
        var ai=new CodexIntelligence(new AiConfig("codex","codex-default",""),json,(args,dir)->{directories.add(dir);return child;},600);
        assertThatThrownBy(()->ai.call("test","test",Map.of(),Map.of(),100)).hasMessageContaining("600초");
        assertThat(child.isAlive()).isFalse();assertThat(directories.get(0)).doesNotExist();
    }
}
