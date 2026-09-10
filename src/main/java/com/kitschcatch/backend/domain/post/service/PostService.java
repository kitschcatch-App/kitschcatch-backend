package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlRequest;
import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlResponse;
import com.kitschcatch.backend.domain.post.dto.CreatePostRequest;
import com.kitschcatch.backend.domain.post.dto.PostImageResponse;
import com.kitschcatch.backend.domain.post.dto.PostImageUploadItemRequest;
import com.kitschcatch.backend.domain.post.dto.PostImageUploadUrlResponse;
import com.kitschcatch.backend.domain.post.dto.PostResponse;
import com.kitschcatch.backend.domain.post.dto.UpdatePostRequest;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.PostImage;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class PostService {

	private static final int MAX_IMAGE_COUNT = 10;

	private final PostRepository postRepository;
	private final UserRepository userRepository;
	private final PostImageStorage postImageStorage;
	private final TransactionOperations transactionOperations;
	private final PurchaseOrderRepository orderRepository;

	@Autowired
	public PostService(
		PostRepository postRepository,
		UserRepository userRepository,
		PostImageStorage postImageStorage,
		PlatformTransactionManager transactionManager,
		PurchaseOrderRepository orderRepository
	) {
		this(postRepository, userRepository, postImageStorage, new TransactionTemplate(transactionManager), orderRepository);
	}

	PostService(
		PostRepository postRepository,
		UserRepository userRepository,
		PostImageStorage postImageStorage,
		TransactionOperations transactionOperations,
		PurchaseOrderRepository orderRepository
	) {
		this.postRepository = postRepository;
		this.userRepository = userRepository;
		this.postImageStorage = postImageStorage;
		this.transactionOperations = transactionOperations;
		this.orderRepository = orderRepository;
	}

	public CreatePostImageUploadUrlResponse createImageUploadUrls(
		Long userId,
		CreatePostImageUploadUrlRequest request
	) {
		List<PostImageUploadUrlResponse> images = request.images().stream()
			.map(image -> createImageUploadUrl(userId, image))
			.toList();
		return new CreatePostImageUploadUrlResponse(images);
	}

	public PostResponse createPost(Long userId, CreatePostRequest request) {
		List<String> imageKeys = requireImageKeys(request.imageKeys());
		verifyImageKeys(userId, imageKeys);
		return transactionOperations.execute(status -> {
			User user = userRepository.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));

			Post post = Post.builder()
				.user(user)
				.title(request.title())
				.description(request.description())
				.price(request.price())
				.productCategory(request.productCategory())
				.productCondition(request.productCondition())
				.productStatus(ProductStatus.ON_SALE)
				.build();
			addImages(post, imageKeys);

			return toResponse(postRepository.save(post));
		});
	}

	@Transactional(readOnly = true)
	public Page<PostResponse> getPosts(Pageable pageable) {
		return postRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc(pageable)
			.map(this::toResponse);
	}

	@Transactional(readOnly = true)
	public PostResponse getPost(Long postId) {
		return toResponse(findActivePostDetail(postId));
	}

	public PostResponse updatePost(Long userId, Long postId, UpdatePostRequest request) {
		List<String> imageKeys = null;
		if (request.imageKeys() != null) {
			imageKeys = requireImageKeys(request.imageKeys());
			verifyImageKeys(userId, imageKeys);
		}
		List<String> replacementImageKeys = imageKeys;

		return transactionOperations.execute(status -> {
			Post post = findActivePost(postId);
			validateOwner(userId, post);
			validateNoActiveOrder(post);

			post.update(
				request.title(),
				request.description(),
				request.price(),
				request.productCategory(),
				request.productCondition(),
				request.productStatus()
			);

			if (replacementImageKeys != null) {
				post.replaceImages(replacementImageKeys);
			}

			return toResponse(post);
		});
	}

	public void deletePost(Long userId, Long postId) {
		transactionOperations.execute(status -> {
			Post post = findActivePost(postId);
			validateOwner(userId, post);
			validateNoActiveOrder(post);
			post.delete();
			return null;
		});
	}

	private PostImageUploadUrlResponse createImageUploadUrl(Long userId, PostImageUploadItemRequest image) {
		PostImageUploadUrl uploadUrl = postImageStorage.createUploadUrl(
			userId,
			image.originalFileName(),
			image.contentType()
		);
		return new PostImageUploadUrlResponse(
			uploadUrl.uploadUrl(),
			uploadUrl.imageKey(),
			uploadUrl.imageUrl(),
			uploadUrl.expiresIn()
		);
	}

	private Post findActivePost(Long postId) {
		return postRepository.findByIdForUpdate(postId)
			.filter(post -> post.getDeletedAt() == null)
			.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
	}

	private Post findActivePostDetail(Long postId) {
		return postRepository.findDetailByIdAndDeletedAtIsNull(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
	}

	private void validateOwner(Long userId, Post post) {
		if (!post.getUser().getId().equals(userId)) {
			throw new BusinessException(ErrorCode.POST_FORBIDDEN);
		}
	}

	private void validateNoActiveOrder(Post post) {
		if (post.getActiveOrderNumber() != null || orderRepository.existsByPostIdAndOrderStatusIn(
			post.getId(), List.of(OrderStatus.PENDING, OrderStatus.PAID))) {
			throw new BusinessException(ErrorCode.POST_TRANSACTION_IN_PROGRESS);
		}
	}

	private List<String> requireImageKeys(List<String> imageKeys) {
		if (imageKeys == null || imageKeys.isEmpty()) {
			throw new BusinessException(ErrorCode.POST_IMAGE_REQUIRED);
		}
		if (imageKeys.size() > MAX_IMAGE_COUNT) {
			throw new BusinessException(ErrorCode.POST_IMAGE_INVALID);
		}
		if (imageKeys.size() != Set.copyOf(imageKeys).size()) {
			throw new BusinessException(ErrorCode.POST_IMAGE_INVALID);
		}
		return imageKeys;
	}

	private void verifyImageKeys(Long userId, List<String> imageKeys) {
		for (String imageKey : imageKeys) {
			if (!StringUtils.hasText(imageKey) || !postImageStorage.isOwnedPostImageKey(userId, imageKey)) {
				throw new BusinessException(ErrorCode.POST_IMAGE_INVALID);
			}
			if (!postImageStorage.exists(imageKey)) {
				throw new BusinessException(ErrorCode.POST_IMAGE_NOT_UPLOADED);
			}
		}
	}

	private void addImages(Post post, List<String> imageKeys) {
		for (int i = 0; i < imageKeys.size(); i++) {
			post.addImage(imageKeys.get(i), i);
		}
	}

	private PostResponse toResponse(Post post) {
		List<PostImageResponse> images = post.getImages().stream()
			.map(this::toImageResponse)
			.toList();
		return new PostResponse(
			post.getId(),
			post.getUser().getId(),
			post.getUser().getNickname(),
			post.getTitle(),
			post.getDescription(),
			post.getPrice(),
			post.getProductCategory(),
			post.getProductCondition(),
			post.getProductStatus(),
			images,
			post.getCreatedAt(),
			post.getUpdatedAt()
		);
	}

	private PostImageResponse toImageResponse(PostImage image) {
		return new PostImageResponse(
			image.getObjectKey(),
			postImageStorage.imageUrl(image.getObjectKey()),
			image.getSortOrder()
		);
	}
}
