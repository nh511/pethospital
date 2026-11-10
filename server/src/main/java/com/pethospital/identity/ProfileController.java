package com.pethospital.identity;

import com.pethospital.identity.ProfileDtos.MenuView;
import com.pethospital.identity.ProfileDtos.ProfilePatchRequest;
import com.pethospital.identity.ProfileDtos.UserSelfView;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {
    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    ResponseEntity<ApiResponse<UserSelfView>> current() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(profiles.current()));
    }

    @PatchMapping
    ResponseEntity<ApiResponse<UserSelfView>> update(@Valid @RequestBody ProfilePatchRequest request) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(profiles.update(request)));
    }

    @GetMapping("/menus")
    ResponseEntity<ApiResponse<List<MenuView>>> menus() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(profiles.menus()));
    }
}
