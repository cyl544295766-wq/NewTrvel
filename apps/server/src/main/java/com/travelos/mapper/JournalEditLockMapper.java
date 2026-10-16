package com.travelos.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelos.entity.JournalEditLock;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JournalEditLockMapper extends BaseMapper<JournalEditLock> {
}
