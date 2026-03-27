package com.chatagent.llm;

import com.chatagent.config.DashScopeProperties;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ModelAllowlist {

    private final DashScopeProperties dashScopeProperties;

    public boolean isAllowed(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        Set<String> allowed = new HashSet<>(dashScopeProperties.getAllowedModels());
        if (allowed.isEmpty()) {
            return true;
        }
        return allowed.contains(model);
    }
}

