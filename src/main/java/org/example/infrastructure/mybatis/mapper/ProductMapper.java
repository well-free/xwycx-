package org.example.infrastructure.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.infrastructure.mybatis.entity.ProductEntity;

@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {
    @Update("update products set stock = stock - #{quantity}, updated_at = current_timestamp where id = #{productId} and status = 'ON_SHELF' and stock >= #{quantity}")
    int deductStock(@Param("productId") long productId, @Param("quantity") long quantity);

    @Update("update products set stock = stock + #{quantity}, updated_at = current_timestamp where id = #{productId}")
    int restoreStock(@Param("productId") long productId, @Param("quantity") long quantity);
}
