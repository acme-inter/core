package com.acme.core.query;

import com.acme.core.util.GuardUtil;
import com.acme.core.util.MsgUtil;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

@Component
public class PagedQueryFactory {

  private final DatabaseClient databaseClient;
  private final MsgUtil        msgUtil;
  private final GuardUtil guardUtil;

  public PagedQueryFactory(DatabaseClient databaseClient,
                           MsgUtil msgUtil,
                           GuardUtil guardUtil) {
    this.databaseClient = databaseClient;
    this.msgUtil        = msgUtil;
    this.guardUtil      = guardUtil;
  }

  public <T, R> PagedQueryBuilder<T, R> create(
      Function<Row, T> rowMapper,
      Function<List<T>, Flux<R>> converter) {

    PagedQueryBuilder<T, R> builder =
        new PagedQueryBuilder<>(databaseClient, msgUtil, guardUtil);
    builder.rowMapper(rowMapper);
    builder.converter(converter);
    return builder;
  }
}