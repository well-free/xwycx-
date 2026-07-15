package org.example.infrastructure.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.infrastructure.mybatis.entity.StoreSettingsEntity;

@Mapper
public interface StoreSettingsMapper extends BaseMapper<StoreSettingsEntity> {
}
