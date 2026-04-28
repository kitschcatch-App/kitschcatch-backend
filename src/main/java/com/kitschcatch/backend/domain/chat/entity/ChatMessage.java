package com.kitschcatch.backend.domain.chat.entity;

import com.kitschcatch.backend.domain.user.entity.User;
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
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "chat_room_id", nullable = false, foreignKey = @ForeignKey(name = "fk_chat_messages_chat_room"))
	private ChatRoom chatRoom;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sender_id", nullable = false, foreignKey = @ForeignKey(name = "fk_chat_messages_sender"))
	private User sender;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private MessageType messageType;

	@Column(length = 1000)
	private String content;

	@Column(length = 2048)
	private String imageUrl;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	private boolean isRead;

	@Builder
	private ChatMessage(
		ChatRoom chatRoom,
		User sender,
		MessageType messageType,
		String content,
		String imageUrl,
		boolean isRead
	) {
		this.chatRoom = chatRoom;
		this.sender = sender;
		this.messageType = messageType;
		this.content = content;
		this.imageUrl = imageUrl;
		this.isRead = isRead;
	}
}
