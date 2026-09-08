package th.co.sena.workercms;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalSecurityFilter extends OncePerRequestFilter {
    private final StateService state;
    public LocalSecurityFilter(StateService state) { this.state = state; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String host = request.getHeader("Host"); String port = ":" + request.getServerPort();
        boolean valid = host != null && (host.equals("localhost" + port) || host.equals("127.0.0.1" + port));
        if (!valid) { error(response, 403, "Host not allowed"); return; }
        if ("POST".equals(request.getMethod()) && !state.token().equals(request.getHeader("X-CSRF-Token"))) {
            error(response, 403, "กรุณาเปิดหน้าเว็บใหม่"); return;
        }
        response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'");
        chain.doFilter(request, response);
    }
    private void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_JSON_VALUE); response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
