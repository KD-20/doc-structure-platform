package com.docstructure.platform.config;

import com.docstructure.platform.auth.JwtAuthFilter;
import com.docstructure.platform.auth.JwtService;
import com.docstructure.platform.common.RequestLoggingFilter;
import com.docstructure.platform.guestaccess.GuestAuthFilter;
import com.docstructure.platform.guestaccess.GuestLinkService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService) {
        return new JwtAuthFilter(jwtService);
    }

    @Bean
    public GuestAuthFilter guestAuthFilter(GuestLinkService guestLinkService) {
        return new GuestAuthFilter(guestLinkService);
    }

    @Bean
    public RequestLoggingFilter requestLoggingFilter() {
        return new RequestLoggingFilter();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                                     GuestAuthFilter guestAuthFilter,
                                                     RequestLoggingFilter requestLoggingFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Without this, Spring Security's default Http403ForbiddenEntryPoint returns
                // 403 for BOTH "not authenticated" and "authenticated but wrong role" — losing
                // a distinction API clients rely on (401 = log in again, 403 = wrong permissions).
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/guest/**", "/api/public/**", "/actuator/health",
                                "/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**",
                                "/*.js", "/*.css", "/*.svg", "/*.ico").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Non-API paths (SPA client-side routes) are served statically; the
                        // SPA itself enforces auth by redirecting to /login when unauthenticated.
                        .anyRequest().permitAll())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Registered after jwtAuthFilter (this call comes second) so JWT auth gets
                // first chance at the request; GuestAuthFilter itself also no-ops if JWT
                // already authenticated it. See GuestAuthFilter's class javadoc.
                .addFilterBefore(guestAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Outermost of the three: runs before jwtAuthFilter so requestId is in MDC
                // before authentication happens, and its finally block is therefore the last
                // to run, so its single MDC.clear() cleans up everything the auth filters add
                // downstream too. See RequestLoggingFilter's own javadoc.
                .addFilterBefore(requestLoggingFilter, JwtAuthFilter.class);
        return http.build();
    }
}
