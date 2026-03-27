package com.chatagent.llm;

import com.chatagent.config.DashScopeProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelsController {

    private final DashScopeProperties dashScopeProperties;

    @GetMapping
    public List<String> list() {
        return dashScopeProperties.getAllowedModels();
    }
}

