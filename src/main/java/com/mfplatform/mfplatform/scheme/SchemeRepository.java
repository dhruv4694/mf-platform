package com.mfplatform.mfplatform.scheme;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SchemeRepository extends JpaRepository<Scheme, Long> {
    Optional<Scheme> findBySchemeCode(String schemeCode);
}
