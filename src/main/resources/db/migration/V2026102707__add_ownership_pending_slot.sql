-- 같은 제품에 대기(PENDING) 중인 이전 요청이 둘 이상 생기지 않도록 DB에서 막는다(NFR-02).
-- 부분 유니크 인덱스는 H2에서 쓸 수 없어, listings.active_slot과 같이 NULL을 허용하는 슬롯 열에 UNIQUE를 건다.
-- 대기 중이면 pending_slot = 1, 그 밖의 상태는 NULL이라 완료·취소·만료 행은 제약에 걸리지 않는다.
ALTER TABLE ownership_transfers ADD COLUMN pending_slot SMALLINT;

-- 이미 중복된 대기 요청이 있으면 가장 최근 것만 남기고 나머지는 만료 처리한다.
UPDATE ownership_transfers
SET status = 'EXPIRED'
WHERE status = 'PENDING'
  AND EXISTS (
      SELECT 1
      FROM ownership_transfers newer
      WHERE newer.product_id = ownership_transfers.product_id
        AND newer.status = 'PENDING'
        AND newer.id > ownership_transfers.id);

UPDATE ownership_transfers SET pending_slot = 1 WHERE status = 'PENDING';

ALTER TABLE ownership_transfers
    ADD CONSTRAINT uk_transfers_pending_product UNIQUE (product_id, pending_slot);

ALTER TABLE ownership_transfers
    ADD CONSTRAINT ck_transfers_pending_slot CHECK (
        (status = 'PENDING' AND pending_slot IS NOT NULL AND pending_slot = 1)
        OR (status <> 'PENDING' AND pending_slot IS NULL));
