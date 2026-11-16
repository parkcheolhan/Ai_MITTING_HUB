package kr.meetinghub;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local settings may only be changed by local pages or direct API clients. */
@Component
public class SettingsOriginFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        // Set only by RemoteAccessFilter after exact-origin checks and token authentication.
        if(Boolean.TRUE.equals(request.getAttribute(RemoteAccessFilter.AUTHENTICATED))){chain.doFilter(request,response);return;}
        if(request.getRequestURI().startsWith("/api/settings/")&&!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) {
            String origin=request.getHeader("Origin");boolean trusted=true;
            if(origin!=null) {
                try {var uri=URI.create(origin);trusted=Set.of("http","https").contains(uri.getScheme())&&Set.of("localhost","127.0.0.1","[::1]","::1").contains(uri.getHost());}
                catch(Exception e){trusted=false;}
            }
            if(!trusted||"cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
                response.setStatus(403);response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"연동 설정은 localhost 화면에서 변경하세요.\"}");return;
            }
        }
        chain.doFilter(request,response);
    }
}
