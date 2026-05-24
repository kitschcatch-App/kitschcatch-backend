package com.kitschcatch.backend.domain.chat.entity;

import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(
		name = "chat_rooms",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_chat_rooms_post_buyer_seller",
						columnNames = {"post_id", "buyer_id", "seller_id"}
				)
		}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false, foreignKey = @ForeignKey(name = "fk_chat_rooms_post"))
	private Post post;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "buyer_id", nullable = false, foreignKey = @ForeignKey(name = "fk_chat_rooms_buyer"))
	private User buyer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "seller_id", nullable = false, foreignKey = @ForeignKey(name = "fk_chat_rooms_seller"))
	private User seller;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private LocalDateTime buyerDeletedAt;

	private LocalDateTime sellerDeletedAt;

	@Column(length = 1000)
	private String lastMessageContent;

	private LocalDateTime lastMessageAt;

	@Builder
	private ChatRoom(Post post, User buyer, User seller, String lastMessageContent, LocalDateTime lastMessageAt) {
		this.post = post;
		this.buyer = buyer;
		this.seller = seller;
		this.lastMessageContent = lastMessageContent;
		this.lastMessageAt = lastMessageAt;
	}

	public static ChatRoom create(Post post, User buyer, User seller) {
		return ChatRoom.builder()
				.post(post)
				.buyer(buyer)
				.seller(seller)
				.build();
	}

}
