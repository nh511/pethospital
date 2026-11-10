package com.pethospital.identity;

import com.pethospital.identity.AuthDtos.LoginRequest;
import com.pethospital.identity.AuthDtos.LoginResult;
import com.pethospital.identity.AuthDtos.RegisterRequest;
import com.pethospital.identity.AuthDtos.TokenBundle;
import com.pethospital.identity.AuthDtos.UserView;
import com.pethospital.identity.AuthDtos.WechatLoginRequest;
import com.pethospital.identity.PasswordAuthService.ClientType;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    static final String WEB_REFRESH_COOKIE = "__Host-pet_refresh";

    private final PasswordAuthService authService;
    private final WechatAuthService wechatAuthService;

    public AuthController(PasswordAuthService authService, WechatAuthService wechatAuthService) {
        this.authService = authService;
        this.wechatAuthService = wechatAuthService;
    }

    @PostMapping("/user/register")
    ResponseEntity<ApiResponse<UserView>> register(@Valid @RequestBody RegisterRequest request) {
        UserView user = authService.register(
            request.username(),
            request.password(),
            request.displayName(),
            request.privacyPolicyVersion()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(user));
    }

    @PostMapping("/user/login")
    ResponseEntity<ApiResponse<TokenBundle>> userLogin(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(request.username(), request.password(), ClientType.MINI);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(result.miniBundle()));
    }

    @PostMapping("/wechat/login")
    ResponseEntity<ApiResponse<TokenBundle>> wechatLogin(@Valid @RequestBody WechatLoginRequest request) {
        LoginResult result = wechatAuthService.login(request.code(), request.privacyPolicyVersion());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(result.miniBundle()));
    }

    @PostMapping("/web/login")
    ResponseEntity<ApiResponse<TokenBundle>> webLogin(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(request.username(), request.password(), ClientType.WEB);
        ResponseCookie refreshCookie = ResponseCookie.from(WEB_REFRESH_COOKIE, result.refreshToken())
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/")
            .maxAge(Duration.ofSeconds(result.refreshExpiresIn()))
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(result.webBundle()));
    }
}
