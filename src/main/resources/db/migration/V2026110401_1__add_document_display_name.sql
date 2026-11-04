-- 사용자가 사본을 저장할 때 붙이는 문서 이름. 업로드 파일명은 계속 저장하지 않으며, 이름을 비우면 NULL이다.
ALTER TABLE documents ADD COLUMN IF NOT EXISTS display_name VARCHAR(50);
