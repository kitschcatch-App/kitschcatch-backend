// 검색어 API의 저장 및 설정 계약을 정의한다.
package com.kitschcatch.backend.domain.search;

import jakarta.persistence.*;

@Entity
@Table(name="recent_searches", uniqueConstraints=@UniqueConstraint(columnNames={"user_id", "keyword_key"}), indexes={@Index(name="ix_recent_user_time", columnList="user_id,last_searched_at DESC,id DESC")})
class RecentSearch {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="keyword_key", nullable=false, length=64) private String keywordKey;
    @Column(name="display_keyword", nullable=false, length=1000) private String displayKeyword;
    @Column(name="normalized_keyword", nullable=false, length=1000) private String normalizedKeyword;
    @Column(name="last_searched_at", nullable=false) private java.time.Instant lastSearchedAt;
}
