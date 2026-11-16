package kr.meetinghub;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import jakarta.servlet.FilterChain;
import static org.assertj.core.api.Assertions.*;

class RemoteAccessFilterTest {
    final String token="unit-test-remote-token-32-characters-only";
    final String origin="https://unit.github.io";
    final RemoteAccessFilter filter=new RemoteAccessFilter(token,origin);
    MockHttpServletRequest request(String method,String url,String source,String auth){
        var request=new MockHttpServletRequest(method,url);
        request.setScheme("http");request.setServerName("localhost");request.setServerPort(8080);
        if(source!=null)request.addHeader("Origin",source);
        if(auth!=null)request.addHeader("Authorization","Bearer "+auth);
        return request;
    }
    @Test void requiresConfiguration(){
        assertThatThrownBy(()->new RemoteAccessFilter("",origin)).isInstanceOf(IllegalArgumentException.class);
        for(String bad:new String[]{"","*","https://*.github.io","https://unit.github.io/repo","http://unit.github.io"})
            assertThatThrownBy(()->new RemoteAccessFilter(token,bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void preflightDoesNotRequireToken()throws Exception{
        var req=request("OPTIONS","/api/meetings",origin,null);req.addHeader("Access-Control-Request-Method","POST");req.addHeader("Access-Control-Request-Headers","authorization,content-type");
        var res=new MockHttpServletResponse();filter.doFilter(req,res,(a,b)->{throw new AssertionError("Preflight reached API");});
        assertThat(res.getStatus()).isEqualTo(200);assertThat(res.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
    }
    @Test void blocksUnknownOriginsEvenWithValidToken()throws Exception{
        var res=new MockHttpServletResponse();filter.doFilter(request("POST","/api/settings/ai","https://other.github.io",token),res,(a,b)->{throw new AssertionError("Untrusted origin reached API");});
        assertThat(res.getStatus()).isEqualTo(403);
    }
    @Test void protectsReadsWritesAndMediaWithoutOriginToo()throws Exception{
        for(String path:new String[]{"/api/meetings","/api/settings","/api/settings/ai","/api/meetings/id/assets/file"})for(String auth:new String[]{null,"wrong"}){
            var res=new MockHttpServletResponse();filter.doFilter(request("GET",path,null,auth),res,(a,b)->{throw new AssertionError("Anonymous API access");});assertThat(res.getStatus()).isEqualTo(401);
        }
    }
    @Test void authenticatedPagesCanSaveSettings()throws Exception{
        var req=request("PUT","/api/settings/ai",origin,token);req.addHeader("Sec-Fetch-Site","cross-site");
        var res=new MockHttpServletResponse();boolean[] reached={false};
        filter.doFilter(req,res,(a,b)->new SettingsOriginFilter().doFilter(a,b,(x,y)->reached[0]=true));
        assertThat(reached[0]).isTrue();assertThat(res.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void localOnlySettingsStillRejectForeignPages()throws Exception{
        var res=new MockHttpServletResponse();new SettingsOriginFilter().doFilter(request("PUT","/api/settings/ai",origin,null),res,(a,b)->{throw new AssertionError("Settings bypass");});assertThat(res.getStatus()).isEqualTo(403);
    }
}
