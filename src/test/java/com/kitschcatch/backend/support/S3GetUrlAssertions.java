// AWS 연결 없이 조회 URL의 GET 서명과 객체 경로·유효기간을 검증한다.
package com.kitschcatch.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class S3GetUrlAssertions {

	private S3GetUrlAssertions() {}

	public static void assertGetUrl(String url, String bucket, String key, long expiresIn,
		String accessKey, String secretKey) {
		URI uri = URI.create(url);
		assertThat(uri.getScheme()).isEqualTo("https");
		assertThat(uri.getHost()).isEqualTo(bucket + ".s3.ap-northeast-2.amazonaws.com");
		assertThat(uri.getPath()).isEqualTo("/" + key);
		assertThat(uri.getFragment()).isNull();
		assertThat(uri.getRawQuery()).isNotBlank();
		Map<String, String> query = Arrays.stream(uri.getRawQuery().split("&"))
			.map(part -> part.split("=", 2))
			.collect(Collectors.toMap(part -> part[0], part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
		assertThat(query).containsEntry("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
			.containsEntry("X-Amz-Expires", Long.toString(expiresIn))
			.containsEntry("X-Amz-SignedHeaders", "host");
		String credential = query.get("X-Amz-Credential");
		assertThat(credential).startsWith(accessKey + "/").endsWith("/ap-northeast-2/s3/aws4_request");
		String scope = credential.substring(credential.indexOf('/') + 1);
		String canonicalQuery = Arrays.stream(uri.getRawQuery().split("&"))
			.filter(part -> !part.startsWith("X-Amz-Signature="))
			.sorted().collect(Collectors.joining("&"));
		String canonicalRequest = "GET\n" + uri.getRawPath() + "\n" + canonicalQuery
			+ "\nhost:" + uri.getHost() + "\n\nhost\nUNSIGNED-PAYLOAD";
		try {
			String requestHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
			String stringToSign = "AWS4-HMAC-SHA256\n" + query.get("X-Amz-Date") + "\n" + scope + "\n" + requestHash;
			byte[] signingKey = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
			for (String part : scope.split("/")) {
				signingKey = hmac(signingKey, part);
			}
			assertThat(query.get("X-Amz-Signature")).isEqualTo(HexFormat.of().formatHex(hmac(signingKey, stringToSign)));
		} catch (GeneralSecurityException exception) {
			throw new AssertionError("GET 서명 검증 실패", exception);
		}
	}

	private static byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
	}
}
