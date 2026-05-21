package com.kitschcatch.backend.domain.post.entity;

import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_posts_user"))
	private User user;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 1000)
	private String description;

	@Column(nullable = false)
	private Long price;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ProductCategory productCategory;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ProductCondition productCondition;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ProductStatus productStatus;

	@OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
	@BatchSize(size = 20)
	@OrderBy("sortOrder ASC")
	private List<PostImage> images = new ArrayList<>();

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private LocalDateTime updatedAt;

	private LocalDateTime deletedAt;

	@Builder
	private Post(
		User user,
		String title,
		String description,
		Long price,
		ProductCategory productCategory,
		ProductCondition productCondition,
		ProductStatus productStatus
	) {
		this.user = user;
		this.title = title;
		this.description = description;
		this.price = price;
		this.productCategory = productCategory;
		this.productCondition = productCondition;
		this.productStatus = productStatus;
	}

	public void update(
		String title,
		String description,
		Long price,
		ProductCategory productCategory,
		ProductCondition productCondition,
		ProductStatus productStatus
	) {
		if (title != null) {
			this.title = title;
		}
		if (description != null) {
			this.description = description;
		}
		if (price != null) {
			this.price = price;
		}
		if (productCategory != null) {
			this.productCategory = productCategory;
		}
		if (productCondition != null) {
			this.productCondition = productCondition;
		}
		if (productStatus != null) {
			this.productStatus = productStatus;
		}
	}

	public void addImage(String objectKey, int sortOrder) {
		images.add(PostImage.of(this, objectKey, sortOrder));
	}

	public void replaceImages(List<String> objectKeys) {
		images.clear();
		for (int i = 0; i < objectKeys.size(); i++) {
			addImage(objectKeys.get(i), i);
		}
	}

	public void delete() {
		this.deletedAt = LocalDateTime.now();
	}
}
