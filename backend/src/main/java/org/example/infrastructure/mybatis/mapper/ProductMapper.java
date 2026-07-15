package org.example.infrastructure.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.infrastructure.mybatis.entity.ProductEntity;

@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {
    @Update("update products set stock = stock - #{quantity}, reserved_stock = reserved_stock + #{quantity}, updated_at = current_timestamp where id = #{productId} and status = 'ON_SHELF' and stock >= #{quantity}")
    int deductStock(@Param("productId") long productId, @Param("quantity") long quantity);

    @Update("update products set stock = stock + #{quantity}, reserved_stock = reserved_stock - #{quantity}, updated_at = current_timestamp where id = #{productId} and reserved_stock >= #{quantity}")
    int restoreStock(@Param("productId") long productId, @Param("quantity") long quantity);

    @Update("update products set reserved_stock = reserved_stock - #{quantity}, sold_stock = sold_stock + #{quantity}, updated_at = current_timestamp where id = #{productId} and reserved_stock >= #{quantity}")
    int commitStock(@Param("productId") long productId, @Param("quantity") long quantity);
}
