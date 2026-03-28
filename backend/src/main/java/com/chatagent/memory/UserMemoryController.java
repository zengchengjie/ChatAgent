package com.chatagent.memory;

import com.chatagent.security.JwtPrincipal;
import com.chatagent.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/memory")
@RequiredArgsConstructor
public class UserMemoryController {

    private final UserMemoryService userMemoryService;

    @GetMapping
    public Map<String, String> list() {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        return userMemoryService.listAsMap(p.userId());
    }

    @PutMapping
    public ResponseEntity<Void> put(@RequestParam @NotBlank String key, @RequestParam @NotBlank String value) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        userMemoryService.put(p.userId(), key.trim(), value.trim());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam(required = false) String key) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        if (key == null || key.isBlank()) {
            userMemoryService.clear(p.userId());
        } else {
            userMemoryService.delete(p.userId(), key.trim());
        }
        return ResponseEntity.noContent().build();
    }
}

