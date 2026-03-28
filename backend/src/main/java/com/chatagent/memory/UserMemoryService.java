package com.chatagent.memory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserMemoryService {

    private final UserMemoryRepository repository;

    @Transactional(readOnly = true)
    public Map<String, String> listAsMap(Long userId) {
        List<UserMemory> rows = repository.findByUserIdOrderByUpdatedAtDesc(userId);
        Map<String, String> out = new LinkedHashMap<>();
        for (UserMemory m : rows) {
            if (!out.containsKey(m.getKey())) {
                out.put(m.getKey(), m.getValue());
            }
        }
        return out;
    }

    @Transactional
    public void put(Long userId, String key, String value) {
        repository
                .findByUserIdAndKey(userId, key)
                .ifPresentOrElse(
                        existing -> {
                            existing.setValue(value);
                            existing.setUpdatedAt(Instant.now());
                            repository.save(existing);
                        },
                        () -> {
                            UserMemory m = new UserMemory();
                            m.setUserId(userId);
                            m.setKey(key);
                            m.setValue(value);
                            m.setUpdatedAt(Instant.now());
                            repository.save(m);
                        });
    }

    @Transactional
    public void delete(Long userId, String key) {
        repository.deleteByUserIdAndKey(userId, key);
    }

    @Transactional
    public void clear(Long userId) {
        repository.deleteByUserId(userId);
    }
}

