package com.mfplatform.mfplatform.folio;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FolioRepository extends JpaRepository<Folio, Long> {

    Page<Folio> findByInvestorId(Long investorId, Pageable pageable);

    // Every folio belonging to any investor under this distributor's book —
    // subquery keeps this repository independent of whether Investor has a
    // mapped JPA relationship to Folio (it doesn't; both use raw FK ids).
    @Query("select f from Folio f where f.investorId in " +
           "(select i.id from Investor i where i.distributorId = :distributorId)")
    Page<Folio> findByInvestorDistributorId(@Param("distributorId") Long distributorId, Pageable pageable);

    boolean existsByFolioNumber(String folioNumber);
}
