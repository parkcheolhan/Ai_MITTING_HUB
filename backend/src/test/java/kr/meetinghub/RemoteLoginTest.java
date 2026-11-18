package kr.meetinghub;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteLoginTest {
    @Test void credentialsAreValidatedAndSessionsExpireOrCanBeRevoked(){
        var clock=mock(Clock.class);var now=Instant.parse("2026-11-18T00:00:00Z");when(clock.instant()).thenReturn(now);var login=new RemoteLogin("unit-admin","unit-password",clock);
        assertThatThrownBy(()->login.login(new RemoteLogin.Input("wrong","unit-password"),"local")).hasMessageContaining("401");
        var session=login.login(new RemoteLogin.Input("unit-admin","unit-password"),"local");assertThat(session.token()).hasSize(43).doesNotContain("unit-password");assertThat(login.accepts(session.token())).isTrue();assertThat(session.toString()).doesNotContain(session.token());
        login.logout(session.token());assertThat(login.accepts(session.token())).isFalse();var next=login.login(new RemoteLogin.Input("unit-admin","unit-password"),"local");when(clock.instant()).thenReturn(now.plus(Duration.ofHours(13)));assertThat(login.accepts(next.token())).isFalse();
    }
    @Test void repeatedFailuresAreLimitedAndInputDiagnosticsDoNotRevealCredentials(){
        var login=new RemoteLogin("unit-admin","unit-password",Clock.systemUTC());for(int i=0;i<10;i++)assertThatThrownBy(()->login.login(new RemoteLogin.Input("unit-admin","wrong"),"local")).hasMessageContaining("401");
        assertThatThrownBy(()->login.login(new RemoteLogin.Input("unit-admin","unit-password"),"local")).hasMessageContaining("429");assertThat(new RemoteLogin.Input("unit-admin","unit-password").toString()).doesNotContain("unit-password");
    }
}
