// 서버를 기동하지 않고 운영자가 명시한 입력·DB·모드로 검수 CSV 도구를 실행한다.
package com.kitschcatch.backend.tools.storecsv;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class StoreCsvCommand {
    private static final Set<String> REQUIRED = Set.of("stores", "hours", "jdbc-url", "db-user", "password-env",
        "schema", "mode", "source", "reviewer", "reviewed-at");

    public static void main(String[] args) {
        try {
            var options = options(args);
            var review = new StoreCsvBatch.Review(options.get("source"), options.get("reviewer"),
                OffsetDateTime.parse(options.get("reviewed-at")).toInstant());
            var batch = StoreCsvBatch.load(Path.of(options.get("stores")), Path.of(options.get("hours")), review);
            String password = System.getenv(options.get("password-env"));
            StoreCsvBatch.require(password != null, "명시한 비밀번호 환경 변수가 설정되지 않았습니다.");
            DriverManager.setLoginTimeout(10);
            try (var connection = DriverManager.getConnection(options.get("jdbc-url"), options.get("db-user"), password)) {
                var result = new StoreCsvImporter().execute(connection, options.get("schema"), batch, options.get("mode").equals("apply"));
                System.out.printf("mode=%s newStores=%d existingStores=%d hours=%d auditId=%s%n",
                    options.get("mode"), result.newStores(), result.existingStores(), result.hours(), result.auditId());
                System.out.printf("storesSha256=%s hoursSha256=%s%n", batch.storesHash(), batch.hoursHash());
            }
        } catch (SQLException exception) {
            // JDBC 오류 메시지는 URL·계정·서버 값을 포함할 수 있어 SQLState만 출력한다.
            System.err.println("CSV 실행 실패. PostgreSQL SQLState=" + exception.getSQLState());
            System.exit(1);
        } catch (Exception exception) {
            System.err.println("CSV 입력·실행 실패. " + safeMessage(exception));
            System.exit(1);
        }
    }

    static Map<String, String> options(String[] args) {
        var options = new HashMap<String, String>();
        StoreCsvBatch.require(args.length == REQUIRED.size() * 2, "모든 필수 --옵션과 값을 명시해야 합니다. docs/issue-55-store-csv.md를 확인하세요.");
        for (int i = 0; i < args.length; i += 2) {
            StoreCsvBatch.require(args[i].startsWith("--"), "옵션 이름은 --로 시작해야 합니다.");
            String key = args[i].substring(2);
            StoreCsvBatch.require(REQUIRED.contains(key) && options.putIfAbsent(key, args[i + 1]) == null
                && !args[i + 1].isBlank(), "알 수 없는 옵션·중복 옵션·빈 값입니다.");
        }
        StoreCsvBatch.require(options.keySet().equals(REQUIRED), "필수 옵션이 없습니다.");
        StoreCsvBatch.require(Set.of("dry-run", "apply").contains(options.get("mode")), "mode는 dry-run 또는 apply이어야 합니다.");
        StoreCsvBatch.require(options.get("schema").matches("[a-z_][a-z0-9_]{0,62}"), "스키마 이름이 올바르지 않습니다.");
        StoreCsvBatch.require(options.get("password-env").matches("[A-Z_][A-Z0-9_]*"), "비밀번호 환경 변수 이름이 올바르지 않습니다.");
        // URL 사용자 정보·검색 경로·임의 JDBC 플러그인 속성을 받지 않는다. TLS 옵션만 지원한다.
        StoreCsvBatch.require(options.get("jdbc-url").matches("jdbc:postgresql://[a-zA-Z0-9.\\-\\[\\]:]+/[a-zA-Z0-9_\\-]+(?:\\?sslmode=(?:disable|require|verify-ca|verify-full))?"),
            "JDBC URL은 명시적 host/database와 선택 sslmode만 지원합니다.");
        return Map.copyOf(options);
    }

    private static String safeMessage(Exception exception) {
        if (exception instanceof IllegalArgumentException) return exception.getMessage();
        return "파일 인코딩·인용 문법·시각 형식 또는 실행 환경을 확인하세요.";
    }
}
