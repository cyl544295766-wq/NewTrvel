package com.travelos.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelos.entity.Booking;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BookingMapper extends BaseMapper<Booking> {
}
