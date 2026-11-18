package kr.meetinghub;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service @Profile("remote")
public class RemoteLogin {
    private final String username,password;private final Clock clock;private final SecureRandom random=new SecureRandom();
    private final Map<String,Instant> sessions=new LinkedHashMap<>();private final Map<String,Attempts> attempts=new HashMap<>();
    private record Attempts(int failures,Instant reset){}
    public record Input(@NotBlank @Size(max=100) String id,@NotBlank @Size(max=8192) String token){@Override public String toString(){return "LoginInput[credentials=REDACTED]";}}
    public record Session(String user,String token,String expiresAt){@Override public String toString(){return "LoginSession[token=REDACTED]";}}
    @org.springframework.beans.factory.annotation.Autowired
    RemoteLogin(@Value("${app.remote.login-id:}") String username,@Value("${app.remote.login-password:}") String password){this(username,password,Clock.systemUTC());}
    RemoteLogin(String username,String password,Clock clock){this.username=username;this.password=password;this.clock=clock;}
    public synchronized Session login(Input input,String address){
        Instant now=clock.instant();attempts.entrySet().removeIf(e->!e.getValue().reset.isAfter(now));
        if(username.isBlank()||password.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"서버의 ID·토큰 로그인 설정을 확인하세요.");
        var rate=attempts.get(address);if(rate!=null&&rate.failures>=10)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"로그인 시도가 많습니다. 1분 후 다시 시도하세요.");
        boolean validId=MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8),input.id().getBytes(StandardCharsets.UTF_8));
        boolean validToken=MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),input.token().getBytes(StandardCharsets.UTF_8));
        if(!(validId&validToken)){
            if(attempts.size()>4096)attempts.clear();attempts.put(address,new Attempts(rate==null?1:rate.failures+1,rate==null?now.plusSeconds(60):rate.reset));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"ID 또는 토큰이 올바르지 않습니다.");
        }
        attempts.remove(address);sessions.entrySet().removeIf(e->!e.getValue().isAfter(now));
        if(sessions.size()>=256)sessions.remove(sessions.keySet().iterator().next());
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Instant expiry=now.plus(Duration.ofHours(12));sessions.put(token,expiry);return new Session(username,token,expiry.toString());
    }
    public synchronized boolean accepts(String token){Instant expiry=sessions.get(token);if(expiry==null)return false;if(!expiry.isAfter(clock.instant())){sessions.remove(token);return false;}return true;}
    public synchronized void logout(String token){sessions.remove(token);}
}

@RestController @Profile("remote") @RequestMapping("/api/auth")
class RemoteLoginApi {
    final RemoteLogin login;RemoteLoginApi(RemoteLogin login){this.login=login;}
    @PostMapping("/login") ResponseEntity<RemoteLogin.Session> login(@Valid @RequestBody RemoteLogin.Input input,jakarta.servlet.http.HttpServletRequest request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(login.login(input,request.getRemoteAddr()));}
    @DeleteMapping("/session") ResponseEntity<Void> logout(@RequestHeader("Authorization") String header){if(header.startsWith("Bearer "))login.logout(header.substring(7));return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
}
