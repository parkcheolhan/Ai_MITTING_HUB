package kr.meetinghub;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import kr.meetinghub.Domain.*;

/** Local Whisper CPU worker, independent of the meeting-analysis AI provider. */
@Service
public class LocalSpeech implements Adapters.SpeechToText {
    interface Runner {JsonNode transcribe(Path input,String language,BooleanSupplier cancelled);}
    final ObjectMapper json;final Path uploadDir,python,modelDir;final int timeoutSeconds;
    final Semaphore gate=new Semaphore(1,true);final Runner runner;
    @org.springframework.beans.factory.annotation.Autowired
    public LocalSpeech(ObjectMapper json,@Value("${app.upload-dir}") String uploadDir,
        @Value("${speech.python:}") String python,@Value("${speech.model-dir:}") String modelDir,@Value("${speech.timeout-seconds:3600}") int timeoutSeconds){
        this.json=json;this.uploadDir=Path.of(uploadDir).toAbsolutePath().normalize();
        Path cwd=Path.of("").toAbsolutePath();Path root=Files.isDirectory(cwd.resolve(".tools"))?cwd:cwd.getParent();
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        this.python=Path.of(python.isBlank()?root.resolve(windows?".tools/speech/Scripts/python.exe":".tools/speech/bin/python").toString():python).toAbsolutePath().normalize();
        this.modelDir=Path.of(modelDir.isBlank()?root.resolve(".tools/speech-models/small").toString():modelDir).toAbsolutePath().normalize();
        this.timeoutSeconds=timeoutSeconds;this.runner=null;
    }
    LocalSpeech(ObjectMapper json,Path uploadDir,Runner runner){this.json=json;this.uploadDir=uploadDir.toAbsolutePath().normalize();this.python=Path.of("python");this.modelDir=Path.of("model");this.timeoutSeconds=30;this.runner=runner;}
    public Map<String,Object> status(){boolean ready=Files.isRegularFile(python)&&Files.isRegularFile(modelDir.resolve("model.bin"))&&Files.isRegularFile(modelDir.resolve("config.json"));var speakerDir=modelDir.toAbsolutePath().getParent().resolve("speakers");boolean speakers=Files.isRegularFile(speakerDir.resolve("segmentation.onnx"))&&Files.isRegularFile(speakerDir.resolve("embedding.onnx"));return Map.of("ready",ready,"speakerReady",speakers,"provider","local-whisper","model","small","message",ready?(speakers?"음성 전사·화자 구분 준비 완료":"음성 전사 준비 완료 · 화자 모델은 scripts/setup-speech.ps1로 준비하세요."):"로컬 음성 엔진을 준비하세요. 서버에서 scripts/setup-speech.ps1을 실행하세요.");}
    @Override public List<Segment> transcribe(Content content){return transcribe(content,()->false);}
    @Override public List<Segment> identifySpeakers(Content content,BooleanSupplier cancelled){
        MeetingService.require(content.assets.size()==1&&!content.segments.isEmpty(),"화자 분석은 원본 파일 하나와 음성 기록이 있는 회의에서 실행하세요.");
        MeetingService.require(content.segments.stream().noneMatch(Segment::estimatedTime),"실제 음성 시각이 있는 기록에서 화자 분석을 실행하세요. 추정 시간을 적용한 기록은 발언별로 참석자를 지정하세요.");
        var asset=content.assets.get(0);MeetingService.require(asset.storageKey()!=null&&asset.storageKey().matches("[a-zA-Z0-9-]+\\.(mp3|wav|m4a|aac|acc|mp4|webm)"),"녹음 파일 경로가 잘못되었습니다.");
        Path path=uploadDir.resolve(asset.storageKey()).normalize();MeetingService.require(path.startsWith(uploadDir)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"녹음 원본을 찾을 수 없습니다.");
        var rows=content.segments.stream().map(s->Map.of("start",s.startMs()/1000.0,"end",s.endMs()/1000.0,"text",s.text())).toList();
        var parsed=parse(run(path,content.language,cancelled,rows,content.participants.size()),0);MeetingService.require(parsed.size()==content.segments.size(),"화자 분석 결과의 발언 수가 일치하지 않습니다.");
        var assignedNames=new HashMap<String,Set<String>>();
        for(int i=0;i<parsed.size();i++)if(content.participants.contains(content.segments.get(i).speaker()))assignedNames.computeIfAbsent(parsed.get(i).speaker(),k->new HashSet<>()).add(content.segments.get(i).speaker());
        var result=new ArrayList<Segment>();for(int i=0;i<parsed.size();i++){var old=content.segments.get(i);var matched=parsed.get(i);var names=assignedNames.getOrDefault(matched.speaker(),Set.of());String name=names.size()==1?names.iterator().next():matched.speaker();result.add(new Segment(old.id(),name,old.startMs(),old.endMs(),old.text(),old.estimatedTime(),matched.speakerAssignment(),matched.speakerSimilarity()));}
        MeetingService.require(content.participants.isEmpty()||result.stream().map(Segment::speaker).distinct().count()<=content.participants.size(),"화자가 지정된 발언자 수를 초과했습니다. 화자 분석을 다시 실행하세요.");return result;
    }
    @Override public List<Segment> transcribe(Content content,BooleanSupplier cancelled){
        if(!content.segments.isEmpty())return content.segments;
        MeetingService.require(!content.assets.isEmpty(),"녹음·녹화 파일 또는 텍스트 기록을 등록하세요.");
        var results=new ArrayList<Segment>();long offset=0;int assetNumber=0;
        for(var asset:content.assets){
            MeetingService.require(asset.storageKey()!=null&&asset.storageKey().matches("[a-zA-Z0-9-]+\\.(mp3|wav|m4a|aac|acc|mp4|webm)"),"녹음 파일 경로가 잘못되었습니다.");
            Path path=uploadDir.resolve(asset.storageKey()).normalize();MeetingService.require(path.startsWith(uploadDir)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"저장된 녹음·녹화 파일을 찾을 수 없습니다.");
            var parsed=parse(runner==null?run(path,Objects.toString(content.language,"ko"),cancelled,null,content.participants.size()):runner.transcribe(path,Objects.toString(content.language,"ko"),cancelled),offset);
            assetNumber++;if(content.assets.size()>1){String prefix="원본 "+assetNumber+" · ";parsed=parsed.stream().map(s->new Segment(s.id(),prefix+s.speaker(),s.startMs(),s.endMs(),s.text(),s.estimatedTime(),s.speakerAssignment(),s.speakerSimilarity())).toList();}
            results.addAll(parsed);offset=parsed.get(parsed.size()-1).endMs();
            MeetingService.require(results.size()<=3000,"녹음이 너무 깁니다. 회의를 나누어 등록하세요.");
        }
        return results;
    }
    static List<Segment> parse(JsonNode root,long offset){
        String error=root.path("error").asText();
        MeetingService.require(!error.equals("SPEAKER_MODEL_MISSING"),"화자 구분 모델을 준비하세요. 서버에서 scripts/setup-speech.ps1을 실행하세요.");
        MeetingService.require(error.isEmpty(),switch(error){case "NO_AUDIO"->"녹화 파일에 음성 트랙이 없습니다.";case "NO_SPEECH"->"음성을 인식하지 못했습니다. 무음 여부와 녹음 품질을 확인하세요.";case "MODEL_MISSING"->"음성 전사 모델이 없습니다. 서버에서 음성 엔진을 준비하세요.";case "TOO_LONG"->"녹음이 너무 깁니다. 4시간 이내 파일로 나누어 등록하세요.";default->"녹음·녹화 파일을 해석하지 못했습니다. 파일이 정상 재생되는지 확인하세요.";});
        var rows=root.path("segments");MeetingService.require(rows.isArray()&&!rows.isEmpty()&&rows.size()<=3000,"음성 전사 결과가 비어 있거나 너무 큽니다.");
        var segments=new ArrayList<Segment>();long previous=offset;int size=0;
        for(var row:rows){
            double start=row.path("start").asDouble(-1),end=row.path("end").asDouble(-1);String text=row.path("text").asText("").strip();
            MeetingService.require(Double.isFinite(start)&&Double.isFinite(end)&&start>=0&&end>=start&&end<=14400&&!text.isBlank(),"음성 전사 결과의 시각·텍스트를 확인하세요.");
            long startMs=offset+Math.round(start*1000),endMs=offset+Math.round(end*1000);
            MeetingService.require(startMs>=previous,"음성 전사 구간 순서가 잘못되었습니다.");size+=text.length();MeetingService.require(size<=200000,"전사문이 너무 깁니다. 녹음을 나누어 등록하세요.");
            String speaker=row.path("speaker").asText("화자 미분리");MeetingService.require(speaker.matches("화자 (?:[1-9][0-9]{0,2}|미지정|미분리)"),"화자 분석 결과를 확인하세요.");
            String assignment=row.hasNonNull("speakerAssignment")?row.path("speakerAssignment").asText():null;
            Double similarity=row.path("speakerSimilarity").isNumber()?row.path("speakerSimilarity").asDouble():null;
            segments.add(new Segment(UUID.randomUUID().toString(),speaker,startMs,endMs,text,false,assignment,similarity));previous=startMs;
        }
        return segments;
    }
    JsonNode run(Path input,String language,BooleanSupplier cancelled){
        return run(input,language,cancelled,null);
    }
    JsonNode run(Path input,String language,BooleanSupplier cancelled,Object segments){
        return run(input,language,cancelled,segments,0);
    }
    JsonNode run(Path input,String language,BooleanSupplier cancelled,Object segments,int maxSpeakers){
        MeetingService.require((boolean)status().get("ready"),"음성 엔진이 준비되지 않았습니다. 서버에서 scripts/setup-speech.ps1을 실행하세요.");
        Process process=null;Path temp=null;boolean acquired=false;
        try{
            while(!(acquired=gate.tryAcquire(1,TimeUnit.SECONDS)))if(cancelled.getAsBoolean())throw new IllegalArgumentException("음성 전사를 취소했습니다.");
            if(cancelled.getAsBoolean())throw new IllegalArgumentException("음성 전사를 취소했습니다.");
            temp=Files.createTempDirectory("meeting-speech-");Path script=temp.resolve("transcribe.py"),output=temp.resolve("output.json");
            try(var resource=getClass().getResourceAsStream("/speech/transcribe.py")){if(resource==null)throw new java.io.IOException();Files.copy(resource,script);}
            try(var resource=getClass().getResourceAsStream("/speech/diarize.py")){if(resource==null)throw new java.io.IOException();Files.copy(resource,temp.resolve("diarize.py"));}
            var command=new ArrayList<>(List.of(python.toString(),script.toString(),"--model-dir",modelDir.toString(),"--input",input.toString(),"--output",output.toString(),"--language",language,"--speaker-dir",modelDir.getParent().resolve("speakers").toString()));
            if(maxSpeakers>0)command.addAll(List.of("--max-speakers",Integer.toString(maxSpeakers)));
            if(segments!=null){Path source=temp.resolve("segments.json");Files.writeString(source,json.writeValueAsString(segments),StandardCharsets.UTF_8);command.addAll(List.of("--segments",source.toString()));}
            var builder=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("HF_HUB_OFFLINE","1");builder.environment().put("PYTHONUTF8","1");
            process=builder.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while(!process.waitFor(1,TimeUnit.SECONDS)){
                MeetingService.require(!cancelled.getAsBoolean(),"음성 전사를 취소했습니다.");MeetingService.require(System.nanoTime()<deadline,"음성 전사 시간이 초과되었습니다. 파일을 나누어 다시 등록하세요.");
            }
            MeetingService.require(Files.isRegularFile(output)&&Files.size(output)<=3000000,"음성 엔진 실행에 실패했습니다. 설치 상태와 파일 형식을 확인하세요.");
            return json.readTree(Files.readString(output,StandardCharsets.UTF_8));
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalArgumentException("음성 전사가 중단되었습니다.");}
        catch(java.io.IOException e){throw new IllegalArgumentException("음성 엔진을 실행할 수 없습니다. 서버의 음성 엔진 설치 상태를 확인하세요.");}
        finally{if(process!=null&&process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}if(acquired)gate.release();if(temp!=null)try(var files=Files.walk(temp)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(java.io.IOException ignored){}});}catch(java.io.IOException ignored){}}
    }
}
