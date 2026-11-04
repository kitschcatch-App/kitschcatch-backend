// 검색어 API의 저장 및 설정 계약을 정의한다.
package com.kitschcatch.backend.domain.search;

import jakarta.persistence.*;

@Entity
@Table(name="related_search_keywords", uniqueConstraints=@UniqueConstraint(columnNames={"source_key", "target_key"}), indexes={@Index(name="ix_related_source", columnList="source_key,active,priority,id")})
class RelatedSearch {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="source_key", nullable=false, length=64) private String sourceKey;
    @Column(name="source_keyword", nullable=false, length=1000) private String sourceKeyword;
    @Column(name="target_key", nullable=false, length=64) private String targetKey;
    @Column(name="target_keyword", nullable=false, length=1000) private String targetKeyword;
    @Column(name="priority", nullable=false) private int priority;
    @Column(name="active", nullable=false) private boolean active;
}
