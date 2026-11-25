package lk.sunshine.smartcare.shared;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Clock;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean UserDetailsService userDetailsService(JdbcTemplate db) {
        return email -> db.query("SELECT email,password_hash,role,active FROM app_user WHERE email=?", rs -> {
            if (!rs.next()) throw new org.springframework.security.core.userdetails.UsernameNotFoundException(email);
            return User.withUsername(rs.getString("email")).password(rs.getString("password_hash"))
                    .roles(rs.getString("role")).disabled(!rs.getBoolean("active")).build();
        }, email);
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/api/auth/register", "/api/auth/forgot-password", "/api/auth/verify-reset-code", "/api/auth/reset-password", "/api/auth/csrf", "/api/auth/session", "/error").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/doctors", "/api/doctors/specialties", "/api/doctors/*/slots").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/branches").permitAll()
                .requestMatchers("/api/**").authenticated().anyRequest().permitAll())
            .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                .successHandler((req,res,authentication) -> {
                    String portal=req.getParameter("portal");
                    boolean patient=authentication.getAuthorities().stream().anyMatch(a -> "ROLE_PATIENT".equals(a.getAuthority()));
                    if((!"PATIENT".equals(portal) && !"STAFF".equals(portal)) || ("PATIENT".equals(portal) && !patient) || ("STAFF".equals(portal) && patient)) {
                        SecurityContextHolder.clearContext();
                        if(req.getSession(false)!=null)req.getSession(false).invalidate();
                        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        res.getWriter().write("{\"error\":\"Use the correct patient or staff sign-in\"}");
                        return;
                    }
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE); res.getWriter().write("{\"ok\":true}");
                })
                .failureHandler((req,res,exception) -> { res.setStatus(HttpServletResponse.SC_UNAUTHORIZED); res.setContentType(MediaType.APPLICATION_JSON_VALUE); res.getWriter().write("{\"error\":\"Invalid email or password\"}"); }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout").logoutSuccessHandler((req,res,auth) -> { res.setContentType(MediaType.APPLICATION_JSON_VALUE); res.getWriter().write("{\"ok\":true}"); }))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,e) -> { res.setStatus(401); res.setContentType(MediaType.APPLICATION_JSON_VALUE); res.getWriter().write("{\"error\":\"Please log in\"}"); })
                .accessDeniedHandler((req,res,e) -> { res.setStatus(403); res.setContentType(MediaType.APPLICATION_JSON_VALUE); res.getWriter().write("{\"error\":\"Access denied\"}"); }));
        return http.build();
    }
}
