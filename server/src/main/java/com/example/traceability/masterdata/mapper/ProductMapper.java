package com.example.traceability.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.masterdata.domain.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 产品主数据持久层访问接口。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 按 ID 锁定产品记录（加排他悲观锁 SELECT ... FOR UPDATE）。
     * 用于跨事务串行化规则版本号分配和规则发布检查，杜绝发布竞态与版本冲突。
     *
     * @param id 产品 ID
     * @return 产品实体；若不存在则返回 null
     */
    @Select("SELECT * FROM product WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    Product selectByIdForUpdate(@Param("id") Long id);
}
