package com.chatagent.memory;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMemoryRepository extends JpaRepository<UserMemory, Integer> {
    List<UserMemory> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<UserMemory> findByUserIdAndKey(Long userId, String key);

    void deleteByUserId(Long userId);

    void deleteByUserIdAndKey(Long userId, String key);
}

