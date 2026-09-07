package com.example.traceability.identity.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.dto.CsrfTokenResponse;
import com.example.traceability.identity.dto.CurrentUserResponse;
import com.example.traceability.identity.dto.LoginRequest;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 认证与授权端点控制器。
 * <p>
 * 提供 CSRF 凭据获取、用户登录建立会话及安全退出注销能力。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final AppUserMapper appUserMapper;

    public AuthController(
            AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            AppUserMapper appUserMapper
    ) {
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.appUserMapper = appUserMapper;
    }

    /**
     * 获取当前会话绑定的 CSRF 凭据（匿名可访问）。
     */
    @GetMapping("/csrf")
    public SuccessEnvelope<CsrfTokenResponse> getCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken == null) {
            csrfToken = (CsrfToken) request.getAttribute("_csrf");
        }
        if (csrfToken == null) {
            csrfToken = csrfTokenRepository.loadDeferredToken(request, response).get();
        }
        if (csrfToken == null) {
            csrfToken = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(csrfToken, request, response);
        }

        return SuccessEnvelope.of(new CsrfTokenResponse(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken()
        ));
    }

    /**
     * 用户登录并建立安全服务端会话。
     */
    @PostMapping("/login")
    public SuccessEnvelope<CurrentUserResponse> login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        try {
            Authentication authenticationToken = UsernamePasswordAuthenticationToken.unauthenticated(
                    loginRequest.username().trim(),
                    loginRequest.password()
            );
            Authentication authenticated = authenticationManager.authenticate(authenticationToken);

            // 1. 防御会话固定攻击，轮换 Session ID
            sessionAuthenticationStrategy.onAuthentication(authenticated, request, response);

            // 2. 建立并显式持久化安全上下文
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticated);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            // 3. 更新最后登录时间并返回白名单主体信息
            if (authenticated.getPrincipal() instanceof TraceSecurityPrincipal principal) {
                AppUser userUpdate = new AppUser();
                userUpdate.setId(principal.getUserId());
                userUpdate.setLastLoginAt(LocalDateTime.now(ZoneOffset.UTC));
                appUserMapper.updateById(userUpdate);

                return SuccessEnvelope.of(principal.toCurrentUserResponse());
            }

            throw new BadCredentialsException("认证主体类型不匹配");
        } catch (AuthenticationException ex) {
            log.warn("用户认证未通过: username={}, exceptionType={}",
                    loginRequest.username(), ex.getClass().getSimpleName());
            SecurityContextHolder.clearContext();
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "AUTH_CREDENTIALS_INVALID",
                    "用户名或密码错误", "认证凭据无效或账户不可用");
        }
    }

    /**
     * 退出当前登录会话，返回 204 No Content。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // 会话已失效
            }
        }
        return ResponseEntity.noContent().build();
    }
}
