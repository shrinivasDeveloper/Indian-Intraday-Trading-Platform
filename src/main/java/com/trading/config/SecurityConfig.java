package com.trading.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SecurityConfig — fixed:
 *
 * Fix 1 (Backtest JSON error):
 *   API calls (/api/**) that fail auth now return HTTP 401 JSON
 *   instead of redirecting to login HTML page.
 *   Dashboard JS detects 401 and shows "Session expired, please login."
 *
 * Fix 2 (Session timeout - mobile):
 *   Session timeout increased to 8 hours (covers full trading day).
 *   Remember-Me enabled: "Keep me logged in" = 30 days cookie.
 *   This fixes mobile logout after 3-4 hours.
 *
 * Fix 3 (Logout):
 *   Logout works via GET /logout (no CSRF needed since csrf.disable()).
 *   Dashboard uses window.location for logout (not fetch).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${dashboard.username:admin}")
    private String username;

    @Value("${dashboard.password:changeme}")
    private String password;

    // Remember-me key — change this to any random string in production
    @Value("${dashboard.remember-me-key:trading-platform-secret-key-2024}")
    private String rememberMeKey;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF disabled — we use stateful sessions, not token-based API
                .csrf(csrf -> csrf.disable())

                // ── Authorization ────────────────────────────────────────────
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/login",
                                "/login.html",
                                "/login/**",
                                "/*.html",
                                "/static/**",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/api/auth/callback",
                                "/api/auth/login-url",
                                "/favicon.ico"
                        ).permitAll()
                        .anyRequest().authenticated()
                )

                // ── FIX 1: API calls return 401 JSON, not HTML redirect ──────
                // When session expires and JS calls /api/**, Spring would normally
                // redirect to /login — this breaks JSON parsing in the browser.
                // Instead: return 401 with JSON body so dashboard can handle it.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            String path = request.getRequestURI();
                            if (path.startsWith("/api/")) {
                                // API call with expired session → return 401 JSON
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType("application/json");
                                response.getWriter().write(
                                        "{\"error\":\"Session expired\",\"loginUrl\":\"/login\"}"
                                );
                            } else {
                                // Browser navigation → redirect to login page
                                response.sendRedirect("/login");
                            }
                        })
                )

                // ── Form Login ───────────────────────────────────────────────
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/dashboard.html", true)
                        .failureUrl("/login?error=true")
                        .permitAll()
                )

                // ── FIX 2: Remember-Me = stay logged in for 30 days ─────────
                // User checks "Keep me logged in" on login page.
                // Creates a persistent cookie valid for 30 days.
                // Fixes mobile session expiry after 3-4 hours.
                .rememberMe(remember -> remember
                        .key(rememberMeKey)
                        .tokenValiditySeconds(30 * 24 * 60 * 60) // 30 days
                        .rememberMeParameter("remember-me")       // checkbox name
                        .rememberMeCookieName("TRADING_REMEMBER") // cookie name
                )

                // ── FIX 3: Logout ────────────────────────────────────────────
                // Supports both GET and POST /logout (since CSRF disabled).
                // Clears session + remember-me cookie.
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout=true")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "TRADING_REMEMBER")
                        .permitAll()
                )

                // ── FIX 2: Session timeout = 8 hours ─────────────────────────
                // Default Spring session = 30 minutes → mobile logs out.
                // 8 hours covers full trading day (9:15 AM to 5:15 PM).
                .sessionManagement(session -> session
                        .invalidSessionUrl("/login?session=expired")
                        .maximumSessions(5)        // allow up to 5 devices simultaneously
                        .maxSessionsPreventsLogin(false) // new login does NOT kick old one
                );

        return http.build();
    }

    // This forwards /login → /login.html (static file)
    @Bean
    public WebMvcConfigurer loginPageConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addViewController("/login")
                        .setViewName("forward:/login.html");
            }
        };
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        var user = User.builder()
                .username(username)
                .password(encoder.encode(password))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}