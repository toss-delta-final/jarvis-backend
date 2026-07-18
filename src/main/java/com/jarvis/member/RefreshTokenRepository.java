package com.jarvis.member;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 삭제된 행 수를 반환하는 조건부 삭제 — RT 회전의 단일성 보장(02 D6).
     * 동시 refresh 경합 시 DB가 직렬화해 한쪽만 1을 받고, 0을 받은 쪽은 발급하지 않는다.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    int deleteByTokenHash(@Param("tokenHash") String tokenHash);
}
