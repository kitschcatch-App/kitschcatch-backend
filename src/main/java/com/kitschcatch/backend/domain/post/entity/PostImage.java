package com.kitschcatch.backend.domain.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "post_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostImage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false, foreignKey = @ForeignKey(name = "fk_post_images_post"))
	private Post post;

	@Column(name = "object_key", nullable = false, length = 512)
	private String objectKey;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	private PostImage(Post post, String objectKey, int sortOrder) {
		this.post = post;
		this.objectKey = objectKey;
		this.sortOrder = sortOrder;
	}

	public static PostImage of(Post post, String objectKey, int sortOrder) {
		return new PostImage(post, objectKey, sortOrder);
	}
}
