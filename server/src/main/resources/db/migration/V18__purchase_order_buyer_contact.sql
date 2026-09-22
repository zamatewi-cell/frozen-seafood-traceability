-- 采购单增加买方联系人信息：下游订货时填写姓名/电话/地址，供卖方在"销售单"中查看
ALTER TABLE `purchase_order`
    ADD COLUMN `buyer_contact_name` VARCHAR(80) NULL COMMENT '买方联系人姓名' AFTER `note`,
    ADD COLUMN `buyer_contact_phone` VARCHAR(40) NULL COMMENT '买方联系电话' AFTER `buyer_contact_name`,
    ADD COLUMN `buyer_contact_address` VARCHAR(500) NULL COMMENT '买方收货地址' AFTER `buyer_contact_phone`;
