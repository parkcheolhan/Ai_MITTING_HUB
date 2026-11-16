package kr.meetinghub;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;

/** Uses the official CLI and its saved authentication; never reads authentication tokens. */
public class CodexIntelligence extends OpenAiIntelligence {
    interface Launcher {Process start(List<String> command,Path directory)throws java.io.IOException;}
    final Launcher launcher;final int timeoutSeconds;
    public CodexIntelligence(AiConfig config,ObjectMapper json){this(config,json,
        (command,directory)->appAuthentication(new ProcessBuilder(command).directory(directory.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)).start(),
        configuredTimeout(System.getenv("CODEX_TIMEOUT_SECONDS")));}
    CodexIntelligence(AiConfig config,ObjectMapper json,Launcher launcher,int timeoutSeconds){super(config,json);this.launcher=launcher;this.timeoutSeconds=timeoutSeconds;}
    static ProcessBuilder appAuthentication(ProcessBuilder builder)throws java.io.IOException{
        String override=System.getenv("MEETING_CODEX_HOME");
        Path home=(override==null||override.isBlank()?Path.of("data","codex"):Path.of(override)).toAbsolutePath().normalize();
        Files.createDirectories(home);builder.environment().put("CODEX_HOME",home.toString());return builder;
    }
    static int configuredTimeout(String value){
        if(value==null||value.isBlank())return 600;
        try {int seconds=Integer.parseInt(value);MeetingService.require(seconds>=60&&seconds<=1800,"CODEX_TIMEOUT_SECONDS는 60~1800초로 설정하세요.");return seconds;}
        catch(NumberFormatException e){throw new IllegalArgumentException("CODEX_TIMEOUT_SECONDS는 60~1800초로 설정하세요.");}
    }
    @Override public String mode(){return "codex";}
    static String executable(){
        String explicit=System.getenv("CODEX_CLI_PATH");
        if(explicit!=null&&!explicit.isBlank())return explicit;
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        for(String directory:Objects.toString(System.getenv("PATH"),"").split(java.io.File.pathSeparator)){
            Path candidate=Path.of(directory,windows?"codex.exe":"codex");
            if(Files.isRegularFile(candidate)&&Files.isExecutable(candidate))return candidate.toString();
        }
        if(windows){
            Path root=Path.of(Objects.toString(System.getenv("LOCALAPPDATA"),""),"JetBrains");
            if(Files.isDirectory(root))try(var files=Files.find(root,15,(p,a)->a.isRegularFile()&&p.getFileName().toString().equals("codex.exe"))){
                var found=files.sorted(Comparator.reverseOrder()).findFirst();if(found.isPresent())return found.get().toString();
            }catch(java.io.IOException ignored){}
        }
        throw new IllegalArgumentException("Codex CLI를 찾을 수 없습니다. CLI를 설치하거나 서버의 CODEX_CLI_PATH에 실행 파일 경로를 지정하세요.");
    }
    @Override JsonNode call(String operation,String instructions,Object input,Map<String,Object> schema,int maxOutput){
        Path directory=null;Process process=null;
        try{
            String source=json.writeValueAsString(input);
            MeetingService.require(source.length()<=1000000,"AI 입력이 너무 큽니다. 전사문을 나누어 등록하세요.");
            directory=Files.createTempDirectory("meeting-codex-");
            Path schemaFile=directory.resolve("schema.json"),resultFile=directory.resolve("result.json");
            Files.writeString(schemaFile,json.writeValueAsString(schema),StandardCharsets.UTF_8);
            var command=new ArrayList<>(List.of(executable(),"exec","--ignore-user-config","--ignore-rules","--ephemeral","--skip-git-repo-check","--sandbox","read-only","-c","approval_policy=\"never\"","-c","features.shell_tool=false","-c","project_doc_max_bytes=0","-c","model_reasoning_effort=\"low\"","--color","never","--output-schema",schemaFile.toString(),"--output-last-message",resultFile.toString()));
            if(!config.model().equals("codex-default")){
                MeetingService.require(config.model().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"),"Codex 모델 이름을 확인하세요.");
                command.addAll(List.of("--model",config.model()));
            }
            command.add("-");
            process=launcher.start(command,directory);
            try(var writer=process.outputWriter(StandardCharsets.UTF_8)){
                writer.write(POLICY+"\n도구, 파일 읽기, 명령 실행을 사용하지 않는다. 제공된 JSON 자료만 분석하고 결과 JSON만 반환한다.\n"+instructions+"\n입력 자료:\n"+source);
            }
            MeetingService.require(process.waitFor(timeoutSeconds,TimeUnit.SECONDS),"Codex 분석 제한 시간("+timeoutSeconds+"초)을 초과했습니다. 잠시 후 재시도하거나 CODEX_TIMEOUT_SECONDS 설정을 조정하세요.");
            MeetingService.require(process.exitValue()==0&&Files.isRegularFile(resultFile),"Codex 실행에 실패했습니다. 서버 계정에서 codex login 후 다시 연결 테스트하세요. CLI 버전·모델 권한·사용 한도도 확인하세요.");
            MeetingService.require(Files.size(resultFile)<=2000000,"Codex 응답이 너무 큽니다.");
            JsonNode result=json.readTree(Files.readString(resultFile,StandardCharsets.UTF_8));
            MeetingService.require(result!=null&&result.isObject(),"Codex가 JSON 결과를 반환하지 않았습니다.");return result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalArgumentException("Codex 분석이 중단되었습니다.");}
        catch(java.io.IOException e){throw new IllegalArgumentException("Codex CLI 실행 또는 응답 읽기에 실패했습니다. 설치 경로와 로그인 상태를 확인하세요.");}
        finally{
            if(process!=null&&process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
            if(directory!=null)try(var files=Files.walk(directory)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(java.io.IOException ignored){}});}catch(java.io.IOException ignored){}
        }
    }
}
