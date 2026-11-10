package io.autolog.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autolog.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerFilter extends OncePerRequestFilter {
    private final Db db;
    private final ObjectMapper json;
    public BearerFilter(Db db, ObjectMapper json) { this.db=db; this.json=json; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String header=req.getHeader("Authorization");
        if (header != null) {
            if (!header.startsWith("Bearer ") || header.length()>256) { reject(res); return; }
            var row=db.optional("""
                SELECT c.id AS credential_id,c.company_id,c.admin_id,c.vehicle_id,
                       CASE WHEN c.kind='DEVICE' THEN 'DEVICE' ELSE a.role END AS role
                FROM credentials c LEFT JOIN admins a ON a.id=c.admin_id
                LEFT JOIN vehicles v ON v.id=c.vehicle_id
                WHERE c.token_hash=? AND c.revoked_at IS NULL AND c.expires_at>now()
                AND ((c.kind='ADMIN' AND a.active=true AND a.password_hash IS NOT NULL)
                  OR (c.kind='DEVICE' AND v.active=true))
                """, TokenCodec.hash(header.substring(7)));
            if (row.isEmpty()) { reject(res); return; }
            var r=row.get();
            var actor=new Actor(Db.uuid(r,"companyId"),Db.uuid(r,"adminId"),Db.uuid(r,"vehicleId"),Db.uuid(r,"credentialId"),Db.text(r,"role"));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_"+actor.role()))));
        }
        chain.doFilter(req,res);
    }
    private void reject(HttpServletResponse res) throws IOException {
        res.setStatus(401);res.setContentType("application/json;charset=UTF-8");
        json.writeValue(res.getOutputStream(),ApiErrorHandler.body("UNAUTHORIZED","인증 정보가 없거나 유효하지 않습니다."));
    }
}
