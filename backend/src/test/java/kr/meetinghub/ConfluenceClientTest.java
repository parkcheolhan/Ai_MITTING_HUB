package kr.meetinghub;

import java.net.http.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfluenceClientTest {
    final ObjectMapper json=new ObjectMapper();
    ConfluenceSettings.Config config(String cloud){return new ConfluenceSettings.Config(true,new JiraConfig("cloud","https://unit.atlassian.net","user@example.com","fake-unit-token",cloud,""),"1","개발","","",true);}
    @SuppressWarnings("unchecked") HttpResponse<String> response(int status,String body){var r=(HttpResponse<String>)mock(HttpResponse.class);when(r.statusCode()).thenReturn(status);when(r.body()).thenReturn(body);return r;}
    @Test void scopedTokenUsesConfluenceGatewayAndPaginationCannotChangeHost(){
        var requests=new ArrayList<HttpRequest>();var client=new ConfluenceClient(json,r->{requests.add(r);return response(200,"{\"results\":[{\"id\":\"1\",\"name\":\"개발\",\"key\":\"DEV\"}],\"_links\":{\"next\":\"https://foreign.example/steal?cursor=next%2Bcursor\"}}");});
        var first=client.spaces(config("test-cloud"),"");assertThat(first.nextCursor()).isEqualTo("next+cursor");client.spaces(config("test-cloud"),first.nextCursor());
        assertThat(requests.get(0).uri().toString()).isEqualTo("https://api.atlassian.com/ex/confluence/test-cloud/wiki/api/v2/spaces?limit=50");assertThat(requests.get(1).uri().getHost()).isEqualTo("api.atlassian.com");assertThat(requests.get(1).uri().getRawQuery()).contains("cursor=next%2Bcursor");assertThat(requests.get(0).headers().firstValue("Authorization").orElseThrow()).startsWith("Basic ");
    }
    @Test void nullRootParentAndClassicSiteAreSupported(){var client=new ConfluenceClient(json,r->{assertThat(r.uri().toString()).isEqualTo("https://unit.atlassian.net/wiki/api/v2/pages/42?body-format=storage");return response(200,"{\"id\":\"42\",\"parentId\":null,\"spaceId\":\"1\",\"version\":{\"number\":2}}");});assertThat(client.page(config(""),"42").parentId()).isEmpty();}
    @Test void errorsNeverEchoRemoteBodiesAndAmbiguousCreatesAreMarked(){
        var client=new ConfluenceClient(json,r->response(403,"secret-body-fake-unit-token"));assertThatThrownBy(()->client.spaces(config(""),"")).hasMessageContaining("권한").hasMessageNotContaining("secret-body").hasMessageNotContaining("fake-unit-token");
        var unavailable=new ConfluenceClient(json,r->response(503,"private"));try{unavailable.create(config(""),"회의","<p>내용</p>");throw new AssertionError("Expected failure");}catch(ConfluenceClient.RemoteFailure e){assertThat(e.uncertain).isTrue();}
    }
    @Test void arbitraryDestinationsAreRejectedBeforeTransport(){var client=new ConfluenceClient(json,r->{throw new AssertionError("Must not send");});var bad=new ConfluenceSettings.Config(true,new JiraConfig("cloud","https://foreign.example","user@example.com","fake","",""),"1","","","",true);assertThatThrownBy(()->client.spaces(bad,"")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->client.page(config(""),"../../private")).isInstanceOf(IllegalArgumentException.class);}
    @Test void minutesTemplateTablesListsAndHeadingsBecomeSafeStorageXhtml(){String html=ConfluenceDocument.text("# 주간 회의\n| 안건 | 담당자 |\n| --- | --- |\n| **보안** | <script> |\n\n- 검토\n- 확인\n\n1. 배포\n2. 점검");assertThat(html).contains("<h2>주간 회의</h2>","<table><tbody><tr><th>안건</th>","<strong>보안</strong>","&lt;script&gt;","<ul><li>검토</li><li>확인</li></ul>","<ol><li>배포</li><li>점검</li></ol>").doesNotContain("<script>");}
}
