package com.travelos.config;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.travelos.common.Ids;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Date;

@Configuration
public class MybatisPlusConfig {

  @Bean
  public IdentifierGenerator identifierGenerator() {
    DefaultIdentifierGenerator fallback = DefaultIdentifierGenerator.getInstance();
    return new IdentifierGenerator() {
      @Override
      public Number nextId(Object entity) {
        return fallback.nextId(entity);
      }

      @Override
      public String nextUUID(Object entity) {
        return Ids.next();
      }
    };
  }

  /** Auto-fills createdAt/updatedAt on insert and updatedAt on update, like Prisma @updatedAt. */
  @Bean
  public com.baomidou.mybatisplus.core.handlers.MetaObjectHandler metaObjectHandler() {
    return new com.baomidou.mybatisplus.core.handlers.MetaObjectHandler() {
      @Override
      public void insertFill(MetaObject metaObject) {
        Date now = new Date();
        strictInsertFill(metaObject, "createdAt", Date.class, now);
        strictInsertFill(metaObject, "updatedAt", Date.class, now);
      }

      @Override
      public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", Date.class, new Date());
      }
    };
  }
}
