package kr.meetinghub;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Device authorization is handled by the official CLI; credentials never pass through this API. */
@Service
public class CodexLogin {
    interface Launcher { Process start(List<String> arguments,boolean discard) throws IOException; }
    private final Launcher launcher;
    private final ScheduledExecutorService executor=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"codex-login");t.setDaemon(true);return t;});
    private Process process;
    private String state="IDLE",code="",message="";
    private boolean loggedIn;
    private static final Pattern CODE=Pattern.compile("(?<![A-Z0-9])([A-Z0-9]{4}-[A-Z0-9]{4,5})(?![A-Z0-9])");
    @Autowired public CodexLogin(){this((args,discard)->{
        var command=new ArrayList<String>();command.add(CodexIntelligence.executable());command.addAll(args);
        var builder=new ProcessBuilder(command).redirectErrorStream(true);
        if(discard)builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return CodexIntelligence.appAuthentication(builder).start();
    });}
    CodexLogin(Launcher launcher){this.launcher=launcher;}
    private boolean active(){return state.equals("STARTING")||state.equals("WAITING");}
    public synchronized Map<String,Object> status(){
        if(!active()){
            try{loggedIn=checkLogin();if(loggedIn&&state.equals("IDLE"))message="Codex에 로그인되어 있습니다.";}
            catch(IllegalArgumentException e){message=e.getMessage();loggedIn=false;}
        }
        return view();
    }
    private boolean checkLogin(){
        Process check=null;
        try{
            check=launcher.start(List.of("login","status"),true);
            return check.waitFor(10,TimeUnit.SECONDS)&&check.exitValue()==0;
        }catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        catch(IOException e){throw new IllegalArgumentException("Codex CLI를 실행할 수 없습니다. 설치 경로를 확인하세요.");}
        finally{stop(check);}
    }
    public synchronized Map<String,Object> start(){
        if(active())return view();
        code="";message="인증 코드를 요청하고 있습니다.";state="STARTING";
        try{
            process=launcher.start(List.of("login","--device-auth"),false);
            Process login=process;
            executor.execute(()->read(login));
            executor.schedule(()->expire(login),15,TimeUnit.MINUTES);
        }catch(IOException|IllegalArgumentException e){state="FAILED";message="Codex 로그인을 시작할 수 없습니다. CLI 설치 경로를 확인하세요.";}
        return view();
    }
    private void read(Process login){
        try(var reader=new BufferedReader(new InputStreamReader(login.getInputStream(),StandardCharsets.UTF_8))){
            String line;
            while((line=reader.readLine())!=null){
                String clean=line.replaceAll("\u001B\\[[;\\d]*[ -/]*[@-~]","");
                var match=CODE.matcher(clean);
                if(match.find())synchronized(this){if(process==login&&active()){code=match.group(1);state="WAITING";message="인증 페이지에서 코드를 입력하세요. 완료되면 자동으로 반영됩니다.";}}
            }
            int exit=login.waitFor();
            synchronized(this){if(process!=login||!active())return;
                loggedIn=exit==0&&checkLogin();state=loggedIn?"SUCCEEDED":"FAILED";code="";
                message=loggedIn?"Codex 로그인이 완료되었습니다.":"Codex 로그인에 실패했습니다. 다시 시도하세요. 기기 코드 로그인이 계정 보안 설정에서 허용되어 있어야 합니다.";
            }
        }catch(IOException|InterruptedException e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();synchronized(this){if(process==login&&active()){state="FAILED";code="";message="Codex 로그인이 중단되었습니다. 다시 시도하세요.";}}}
        finally{stop(login);}
    }
    synchronized void expire(Process login){if(process==login&&active()){state="EXPIRED";code="";message="로그인 시간이 만료되었습니다. 다시 시작하세요.";stop(login);}}
    public synchronized Map<String,Object> cancel(){if(active()){state="CANCELLED";code="";message="로그인 요청을 취소했습니다.";stop(process);}return view();}
    private Map<String,Object> view(){return Map.of("state",state,"loggedIn",loggedIn,"userCode",code,"verificationUrl",code.isEmpty()?"":"https://auth.openai.com/codex/device","message",message);}
    private static void stop(Process process){if(process!=null&&process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}}
    @PreDestroy void close(){stop(process);executor.shutdownNow();}
}
