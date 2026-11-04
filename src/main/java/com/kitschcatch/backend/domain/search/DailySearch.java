// 검색어 API의 저장 및 설정 계약을 정의한다.
package com.kitschcatch.backend.domain.search;

import jakarta.persistence.*;

@Entity
@Table(name="search_daily_users", uniqueConstraints=@UniqueConstraint(columnNames={"search_date", "user_id", "keyword_key"}), indexes={@Index(name="ix_search_day_keyword", columnList="search_date,keyword_key")})
class DailySearch {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="keyword_key", nullable=false, length=64) private String keywordKey;
    @Column(name="normalized_keyword", nullable=false, length=1000) private String normalizedKeyword;
    @Column(name="search_date", nullable=false) private java.time.LocalDate searchDate;
}
