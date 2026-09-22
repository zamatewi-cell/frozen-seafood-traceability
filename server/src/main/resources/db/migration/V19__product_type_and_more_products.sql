-- 商品分类：原料(RAW_MATERIAL) vs 加工成品(FINISHED_GOODS)
ALTER TABLE `product`
    ADD COLUMN `product_type` VARCHAR(20) NOT NULL DEFAULT 'FINISHED_GOODS' COMMENT '商品类型 RAW_MATERIAL/FINISHED_GOODS' AFTER `category`;

ALTER TABLE `product`
    ADD CONSTRAINT `chk_product_type` CHECK (`product_type` IN ('RAW_MATERIAL','FINISHED_GOODS'));

-- 既有4个商品标记为成品
UPDATE `product` SET `product_type` = 'FINISHED_GOODS' WHERE `id` IN (1,2,3,4);

-- 新增原料商品（捕捞船长产出）
INSERT INTO `product` (`id`, `product_code`, `public_name`, `scientific_name`, `category`, `product_type`, `specification`, `source_type`, `base_unit_code`, `status`, `created_at`) VALUES
(10, 'P-RAW-0001', '东海野生带鱼(原料)', 'Trichiurus lepturus', 'FISH', 'RAW_MATERIAL', '散装 冷冻', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6)),
(11, 'P-RAW-0002', '南海野生对虾(原料)', 'Litopenaeus vannamei', 'CRUSTACEAN', 'RAW_MATERIAL', '散装 冰鲜', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6)),
(12, 'P-RAW-0003', '北海捕捞鳕鱼(原料)', 'Gadus macrocephalus', 'FISH', 'RAW_MATERIAL', '散装 冷冻', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6)),
(13, 'P-RAW-0004', '渤海湾扇贝(原料)', 'Patinopecten yessoensis', 'SHELLFISH', 'RAW_MATERIAL', '散装 冰鲜', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6)),
(14, 'P-RAW-0005', '东海捕捞鲅鱼(原料)', 'Scomberomorus niphonius', 'FISH', 'RAW_MATERIAL', '散装 冷冻', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6))
ON DUPLICATE KEY UPDATE `public_name` = VALUES(`public_name`);

-- 新增加工成品
INSERT INTO `product` (`id`, `product_code`, `public_name`, `scientific_name`, `category`, `product_type`, `specification`, `source_type`, `base_unit_code`, `status`, `created_at`) VALUES
(20, 'P-PRO-0001', '冷冻带鱼段(成品)', 'Trichiurus lepturus', 'FISH', 'FINISHED_GOODS', '500g/袋 单冻', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6)),
(21, 'P-PRO-0002', '去壳对虾仁(成品)', 'Litopenaeus vannamei', 'CRUSTACEAN', 'FINISHED_GOODS', '2kg/盒 去壳', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6)),
(22, 'P-PRO-0003', '鳕鱼柳(成品)', 'Gadus macrocephalus', 'FISH', 'FINISHED_GOODS', '1kg/袋 冷冻', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6)),
(23, 'P-PRO-0004', '干贝柱(成品)', 'Patinopecten yessoensis', 'SHELLFISH', 'FINISHED_GOODS', '250g/袋 干制', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6)),
(24, 'P-PRO-0005', '鲅鱼丸(成品)', 'Scomberomorus niphonius', 'FISH', 'FINISHED_GOODS', '1kg/袋 速冻', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6)),
(25, 'P-PRO-0006', '海鲜大礼包(成品)', '混合', 'OTHER', 'FINISHED_GOODS', '5kg/箱 综合装', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6))
ON DUPLICATE KEY UPDATE `public_name` = VALUES(`public_name`);
