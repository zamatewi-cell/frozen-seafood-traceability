package com.example.traceability.identity.config;

import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.security.ActivePrincipalVerificationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * 安全体系核心配置类。
 * <p>
 * 基于 Spring Security 7 构建，支持会话认证、CSRF 防护（HttpSessionCsrfTokenRepository）、
 * 会话固定攻击防护以及已登录主体与组织的活性动态复核。
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@Import({RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, SecurityProblemWriter.class})
public class SecurityConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setParameterName("_csrf");
        return repository;
    }

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            ObjectProvider<UserDetailsService> userDetailsService,
            PasswordEncoder passwordEncoder) {
        UserDetailsService uds = userDetailsService.getIfAvailable();
        if (uds != null) {
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
            provider.setPasswordEncoder(passwordEncoder);
            provider.setHideUserNotFoundExceptions(true);
            return new org.springframework.security.authentication.ProviderManager(provider);
        }
        return new org.springframework.security.authentication.ProviderManager(
                new org.springframework.security.authentication.AuthenticationProvider() {
                    @Override
                    public org.springframework.security.core.Authentication authenticate(
                            org.springframework.security.core.Authentication authentication) {
                        throw new org.springframework.security.authentication.BadCredentialsException("认证服务未配置");
                    }

                    @Override
                    public boolean supports(Class<?> authentication) {
                        return true;
                    }
                }
        );
    }

    @Bean
    public ActivePrincipalVerificationFilter activePrincipalVerificationFilter(
            AppUserMapper appUserMapper,
            OrganizationMapper organizationMapper,
            RoleMapper roleMapper,
            SecurityProblemWriter problemWriter) {
        return new ActivePrincipalVerificationFilter(appUserMapper, organizationMapper, roleMapper, problemWriter);
    }

    @Bean
    public FilterRegistrationBean<ActivePrincipalVerificationFilter> activePrincipalVerificationFilterRegistration(
            ObjectProvider<ActivePrincipalVerificationFilter> filterProvider) {
        FilterRegistrationBean<ActivePrincipalVerificationFilter> registration = new FilterRegistrationBean<>();
        filterProvider.ifAvailable(registration::setFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            ObjectProvider<ActivePrincipalVerificationFilter> activePrincipalVerificationFilterProvider
    ) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository))
                .securityContext(securityContext -> securityContext
                        .securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/samples/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/v1/public/traces/*").permitAll()
                        .requestMatchers("/api/v1/auth/logout", "/api/v1/me").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);

        activePrincipalVerificationFilterProvider.ifAvailable(filter ->
                http.addFilterBefore(filter, AuthorizationFilter.class));

        return http.build();
    }
}
