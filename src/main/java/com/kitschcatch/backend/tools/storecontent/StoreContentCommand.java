// 운영자가 지정한 DB와 검수 파일로 매장 상세 정보 등록 도구를 실행한다.
package com.kitschcatch.backend.tools.storecontent;

import static com.kitschcatch.backend.tools.storecontent.StoreContentBatch.require;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class StoreContentCommand {
    private static final Set<String> REQUIRED = Set.of("file", "jdbc-url", "db-user", "password-env", "schema", "mode");
    private static final Set<String> OPTIONAL = Set.of("expected-sha256", "s3-bucket", "s3-region");

    private StoreContentCommand() {}

    public static void main(String[] args) {
        try {
            var options = options(args);
            var batch = StoreContentBatch.load(Path.of(options.get("file")));
            boolean apply = options.get("mode").equals("apply");
            if (apply || options.containsKey("expected-sha256")) {
                require(batch.sha256().equals(options.get("expected-sha256")), "검수 파일 SHA-256이 일치하지 않습니다.");
            }
            String password = System.getenv(options.get("password-env"));
            require(password != null, "지정한 비밀번호 환경 변수가 설정되지 않았습니다.");
            verifyPhotos(options, batch);
            DriverManager.setLoginTimeout(10);
            try (var connection = DriverManager.getConnection(options.get("jdbc-url"), options.get("db-user"), password)) {
                var result = new StoreContentImporter().execute(connection, options.get("schema"), batch, apply);
                System.out.printf("mode=%s stores=%d descriptionChanges=%d photoListChanges=%d amenityListChanges=%d auditId=%s%n",
                    options.get("mode"), result.stores(), result.descriptionsChanged(), result.photoListsChanged(),
                    result.amenityListsChanged(), result.auditId());
                System.out.printf("inputSha256=%s storeIds=%s%n", batch.sha256(),
                    batch.stores().stream().map(StoreContentBatch.StoreEdit::id).toList());
            }
        } catch (SQLException exception) {
            System.err.println("상세 정보 등록 실패. PostgreSQL SQLState=" + exception.getSQLState());
            System.exit(1);
        } catch (S3Exception exception) {
            System.err.println("사진 읽기 검증 실패. S3 HTTP status=" + exception.statusCode());
            System.exit(1);
        } catch (IllegalArgumentException exception) {
            System.err.println("상세 정보 등록 실패. " + exception.getMessage());
            System.exit(1);
        } catch (Exception exception) {
            System.err.println("상세 정보 등록 실패. JSON 형식·파일 읽기 권한·AWS 자격 증명을 확인하세요.");
            System.exit(1);
        }
    }

    static Map<String, String> options(String[] args) {
        var options = new HashMap<String, String>();
        require(args.length % 2 == 0, "모든 옵션에 값을 지정하세요.");
        for (int i = 0; i < args.length; i += 2) {
            require(args[i].startsWith("--"), "옵션 이름은 --로 시작해야 합니다.");
            String key = args[i].substring(2);
            require((REQUIRED.contains(key) || OPTIONAL.contains(key)) && !args[i + 1].isBlank()
                && options.putIfAbsent(key, args[i + 1]) == null, "알 수 없는 옵션·중복 옵션·빈 값입니다.");
        }
        require(options.keySet().containsAll(REQUIRED), "필수 옵션이 없습니다. docs/issue-83-store-details.md를 확인하세요.");
        require(Set.of("dry-run", "apply").contains(options.get("mode")), "mode는 dry-run 또는 apply여야 합니다.");
        require(options.get("schema").matches("[a-z_][a-z0-9_]{0,62}"), "스키마 이름이 올바르지 않습니다.");
        require(options.get("password-env").matches("[A-Z_][A-Z0-9_]*"), "비밀번호 환경 변수 이름을 확인하세요.");
        require(options.get("jdbc-url").matches("jdbc:postgresql://[a-zA-Z0-9.\\-\\[\\]:]+/[a-zA-Z0-9_\\-]+"
            + "(?:\\?sslmode=(?:disable|require|verify-ca|verify-full))?"), "명시적 PostgreSQL host/database와 sslmode만 지원합니다.");
        if (options.get("mode").equals("apply") || options.containsKey("expected-sha256")) {
            require(options.getOrDefault("expected-sha256", "").matches("[0-9a-f]{64}"), "검수 파일 SHA-256을 지정하세요.");
        }
        require(options.containsKey("s3-bucket") == options.containsKey("s3-region"), "사진 버킷과 리전을 함께 지정하세요.");
        if (options.containsKey("s3-bucket")) {
            require(options.get("s3-bucket").matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                && options.get("s3-region").matches("[a-z]{2}(?:-[a-z0-9]+)+-[0-9]+"), "사진 버킷 또는 리전 형식을 확인하세요.");
        }
        return Map.copyOf(options);
    }

    private static void verifyPhotos(Map<String, String> options, StoreContentBatch batch) throws Exception {
        var photos = batch.stores().stream().filter(edit -> edit.photos() != null)
            .flatMap(edit -> edit.photos().stream()).toList();
        if (photos.isEmpty()) return;
        require(options.containsKey("s3-bucket"), "사진을 연결하려면 비공개 버킷과 리전을 지정하세요.");
        try (var s3 = S3Client.builder().region(Region.of(options.get("s3-region")))
            .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30))).build()) {
            var verifier = new StoreContentPhotoVerifier(s3, options.get("s3-bucket"));
            for (var photo : photos) verifier.verify(photo);
        }
    }
}
