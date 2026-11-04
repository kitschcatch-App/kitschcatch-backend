-- 초기 연관어를 방향별로 등록한다. 운영 기획 검수 후 선택 적용하며 기존 관계를 덮어쓰지 않는다.
BEGIN;
INSERT INTO related_search_keywords(source_key,source_keyword,target_key,target_keyword,priority,active) VALUES ('efe45b922f82c8e1841bfa5d62b34ea7e3be4d185446e227772ecb73f2cb63cc','포켓몬','0bfd34bd41f2ed2905524f2d037f543266ce721f7cc9dd821ed9624f0d5c8c04','포켓몬 카드',10,true) ON CONFLICT(source_key,target_key) DO NOTHING;
INSERT INTO related_search_keywords(source_key,source_keyword,target_key,target_keyword,priority,active) VALUES ('efe45b922f82c8e1841bfa5d62b34ea7e3be4d185446e227772ecb73f2cb63cc','포켓몬','1d8ddb6f579ffcdb3bc1797e60617cb7c083ef67387574e0557df28a9a90af18','피카츄',20,true) ON CONFLICT(source_key,target_key) DO NOTHING;
INSERT INTO related_search_keywords(source_key,source_keyword,target_key,target_keyword,priority,active) VALUES ('efe45b922f82c8e1841bfa5d62b34ea7e3be4d185446e227772ecb73f2cb63cc','포켓몬','cdcb51d1e697dd11283eb2ee4d688d6b678e8154c42083749d09c57d9627cd60','포켓몬 피규어',30,true) ON CONFLICT(source_key,target_key) DO NOTHING;
COMMIT;
