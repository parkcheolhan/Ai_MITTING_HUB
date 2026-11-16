package kr.meetinghub;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodexLoginTest {
    static class Child extends Process {
        final CountDownLatch done=new CountDownLatch(1);final InputStream output;int exit;
        Child(String output){this.output=new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));}
        Child finish(int exit){this.exit=exit;done.countDown();return this;}
        public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
        public InputStream getInputStream(){return output;}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public int waitFor()throws InterruptedException{done.await();return exit;}
        public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{return done.await(timeout,unit);}
        public int exitValue(){if(isAlive())throw new IllegalThreadStateException();return exit;}
        public boolean isAlive(){return done.getCount()!=0;}
        public void destroy(){finish(1);}
        public Process destroyForcibly(){destroy();return this;}
        public java.util.stream.Stream<ProcessHandle> descendants(){return java.util.stream.Stream.empty();}
    }
    static void awaitState(CodexLogin login,String state)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<end){if(login.status().get("state").equals(state))return;Thread.sleep(10);}
        assertThat(login.status().get("state")).isEqualTo(state);
    }
    @Test void exposesOnlyDeviceCodeAndTracksSuccessfulLogin()throws Exception{
        var child=new Child("https://auth.openai.com/codex/device\n\u001b[32mABCD-1234\u001b[0m\nsecret-access-token\n");
        var commands=new ArrayList<List<String>>();
        var login=new CodexLogin((args,discard)->{commands.add(args);return discard?new Child("").finish(0):child;});
        try{
            login.start();awaitState(login,"WAITING");
            var state=login.status();assertThat(state.get("userCode")).isEqualTo("ABCD-1234");
            assertThat(state.toString()).doesNotContain("secret-access-token");
            assertThat(state.get("verificationUrl")).isEqualTo("https://auth.openai.com/codex/device");
            login.start();assertThat(commands).containsExactly(List.of("login","--device-auth"));
            child.finish(0);awaitState(login,"SUCCEEDED");
            assertThat(login.status()).containsEntry("loggedIn",true).containsEntry("userCode","");
        }finally{login.close();}
    }
    @Test void cancellationAndExpiryStopThePendingProcess()throws Exception{
        var child=new Child("ABCD-1234\n");var login=new CodexLogin((args,discard)->discard?new Child("").finish(1):child);
        try{login.start();awaitState(login,"WAITING");assertThat(login.cancel()).containsEntry("state","CANCELLED").containsEntry("userCode","");assertThat(child.isAlive()).isFalse();}
        finally{login.close();}
        var expiring=new Child("ABCD-1234\n");var second=new CodexLogin((args,discard)->expiring);
        try{second.start();awaitState(second,"WAITING");second.expire(expiring);assertThat(second.cancel()).containsEntry("state","EXPIRED").containsEntry("userCode","");assertThat(expiring.isAlive()).isFalse();}
        finally{second.close();}
    }
    @Test void currentDeviceCodeWithFiveCharacterSuffixIsDisplayedAndCanRestartAfterExpiry()throws Exception{
        var first=new Child("\u001b[94mA1B2-C3D45\u001b[0m\n");var next=new Child("Z9Y8-X7W65\n");
        var children=new ArrayDeque<Child>(List.of(first,next));
        var login=new CodexLogin((args,discard)->discard?new Child("").finish(1):children.remove());
        try{
            login.start();awaitState(login,"WAITING");assertThat(login.status()).containsEntry("userCode","A1B2-C3D45");
            login.expire(first);assertThat(login.status()).containsEntry("state","EXPIRED").containsEntry("userCode","");
            login.start();awaitState(login,"WAITING");assertThat(login.status()).containsEntry("userCode","Z9Y8-X7W65");
        }finally{login.close();}
    }
    @Test void failedLoginDoesNotExposeCliOutput()throws Exception{
        var child=new Child("private-auth-error\n");var login=new CodexLogin((args,discard)->child);
        try{login.start();child.finish(1);awaitState(login,"FAILED");assertThat(login.status().toString()).doesNotContain("private-auth-error");}
        finally{login.close();}
    }
    @Test void missingExecutableReturnsActionableFailure(){
        var login=new CodexLogin((args,discard)->{throw new IOException("private-path");});
        try{assertThat(login.start()).containsEntry("state","FAILED");assertThat(login.status().toString()).doesNotContain("private-path");}
        finally{login.close();}
    }
}
