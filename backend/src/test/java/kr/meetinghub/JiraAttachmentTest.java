package kr.meetinghub;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class JiraAttachmentTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    JiraConfig config(){return new JiraConfig("cloud","https://example.atlassian.net","test@example.com","unit-token","","TEST");}
    @Test void updatesAdfMinutesAcceptsNoContentAndFindsExactAttachment(){
        var jira=new CloudJira(config(),json,(method,uri,auth,body)->{
            if(method.equals("PUT")){assertThat(body).contains("description","doc","회의록");return new CloudJira.Reply(204,"");}
            return new CloudJira.Reply(200,"{\"fields\":{\"attachment\":[{\"filename\":\"회의.m4a\",\"size\":3}]}}");
        });
        jira.updateDescription("TEST-1","회의록");assertThat(jira.attachmentExists("TEST-1","회의.m4a",3)).isTrue();
        assertThat(jira.attachmentExists("TEST-1","회의.m4a",4)).isFalse();
    }
    @Test void validatesAttachmentSettingsAndVerifiesUploadResponse()throws Exception{
        Path file=temp.resolve("recording.m4a");Files.write(file,new byte[]{1,2,3});var sends=new AtomicInteger();
        var jira=new CloudJira(config(),json,(m,u,a,b)->new CloudJira.Reply(200,"{\"enabled\":true,\"uploadLimit\":100}"),(uri,auth,path,name,type)->{
            sends.incrementAndGet();assertThat(uri.getPath()).endsWith("/issue/TEST-1/attachments");assertThat(auth).startsWith("Basic ");assertThat(Files.readAllBytes(path)).containsExactly(1,2,3);
            return new CloudJira.Reply(200,"[{\"filename\":\"회의.m4a\",\"size\":3}]");
        });
        jira.attach("TEST-1",file,"회의.m4a","audio/mp4");assertThat(sends.get()).isEqualTo(1);
        assertThatThrownBy(()->jira.attach("TEST-1",file,"bad\r\nname.m4a","audio/mp4")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsOversizeOrDisabledAttachmentsBeforeSending()throws Exception{
        Path file=temp.resolve("recording.m4a");Files.write(file,new byte[]{1,2,3});var sends=new AtomicInteger();
        for(String meta:new String[]{"{\"enabled\":false}","{\"enabled\":true,\"uploadLimit\":2}"}){
            var jira=new CloudJira(config(),json,(m,u,a,b)->new CloudJira.Reply(200,meta),(u,a,p,n,t)->{sends.incrementAndGet();throw new AssertionError();});
            assertThatThrownBy(()->jira.attach("TEST-1",file,"회의.m4a","audio/mp4")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(sends.get()).isZero();
    }
    @Test void uploadTimeoutIsUncertainAndPermissionDenialIsDefinite()throws Exception{
        Path file=temp.resolve("recording.m4a");Files.write(file,new byte[]{1});
        for(int status:new int[]{403,503}){
            var jira=new CloudJira(config(),json,(m,u,a,b)->new CloudJira.Reply(200,"{\"enabled\":true,\"uploadLimit\":100}"),(u,a,p,n,t)->new CloudJira.Reply(status,"{}"));
            assertThatThrownBy(()->jira.attach("TEST-1",file,"회의.m4a","audio/mp4")).isInstanceOf(status==503?CloudJira.Uncertain.class:IllegalArgumentException.class);
        }
    }
    @Test void sendsRealMultipartWithOriginalBytesAndCsrfHeader()throws Exception{
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var received=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/attachments",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("X-Atlassian-Token")).isEqualTo("no-check");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).startsWith("multipart/form-data; boundary=");
            received.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("[]".getBytes());exchange.close();
        });server.start();
        try{
            Path file=temp.resolve("voice.m4a");Files.writeString(file,"original-recording-bytes");
            var reply=CloudJira.defaultUploads().send(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/attachments"),"Basic test",file,"회의.m4a","audio/mp4");
            assertThat(reply.status()).isEqualTo(200);assertThat(received.get()).contains("name=\"file\"","filename=\"회의.m4a\"","original-recording-bytes","Content-Type: audio/mp4");
        }finally{server.stop(0);}
    }
}
