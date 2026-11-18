package kr.meetinghub;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Remote mode is fail-closed; local development without this profile is unchanged. */
@Component
@Profile("remote")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RemoteAccessFilter extends OncePerRequestFilter {
    static final String AUTHENTICATED = RemoteAccessFilter.class.getName()+".authenticated";
    private final byte[] token;
    private final CorsConfiguration cors = new CorsConfiguration();
    @org.springframework.beans.factory.annotation.Autowired RemoteLogin login;
    public RemoteAccessFilter(@Value("${app.remote.token:}") String secret,
                              @Value("${app.remote.allowed-origins:}") String origins) {
        if(secret.isBlank() || secret.length()<32)throw new IllegalArgumentException("APP_REMOTE_TOKEN must contain at least 32 characters.");
        token=secret.getBytes(StandardCharsets.UTF_8);
        List<String> allowed=Arrays.stream(origins.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList();
        if(allowed.isEmpty())throw new IllegalArgumentException("APP_REMOTE_ALLOWED_ORIGINS is required.");
        for(String origin:allowed){
            URI uri=URI.create(origin);
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || origin.contains("*") || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().isEmpty())
                throw new IllegalArgumentException("Allowed origins must be exact HTTPS origins without paths.");
        }
        cors.setAllowedOrigins(allowed);
        cors.setAllowedMethods(List.of("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization","Content-Type","Range"));
        cors.setExposedHeaders(List.of("Content-Disposition","Content-Length","Content-Range","Accept-Ranges"));
        cors.setAllowCredentials(false);cors.setMaxAge(600L);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith("/api/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        if(!new DefaultCorsProcessor().processRequest(cors,request,response))return;
        if(CorsUtils.isPreFlightRequest(request))return;
        if(request.getMethod().equals("POST")&&request.getRequestURI().equals("/api/auth/login")){chain.doFilter(request,response);return;}
        String header=request.getHeader("Authorization");
        byte[] supplied=header!=null&&header.startsWith("Bearer ")?header.substring(7).getBytes(StandardCharsets.UTF_8):new byte[0];
        if(!MessageDigest.isEqual(token,supplied)&&(login==null||!login.accepts(new String(supplied,StandardCharsets.UTF_8)))){
            response.setStatus(401);response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"접속 토큰이 없거나 올바르지 않습니다. 서버 연결 화면에서 다시 연결하세요.\"}");return;
        }
        request.setAttribute(AUTHENTICATED,Boolean.TRUE);
        chain.doFilter(request,response);
    }
}
