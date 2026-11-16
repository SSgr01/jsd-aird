package com.jsd.aird.shared.retrieval;

import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bound query settings to the exact JDBC transaction used for ranking. */
public final class HalfVectorSearch {
 private HalfVectorSearch() { }
 public static String type(int dimension) {
  if(dimension<1||dimension>4000)throw new IllegalArgumentException("HNSW dimension must be between 1 and 4000");
  return "halfvec("+dimension+")";
 }
 public static <T> T query(JdbcTemplate jdbc,int efSearch,Supplier<T> query) {
  if(efSearch<1||efSearch>1000)throw new IllegalArgumentException("Invalid HNSW ef_search");
  return new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource()))).execute(status->{
   jdbc.queryForObject("SELECT set_config('hnsw.ef_search', ?, true)",String.class,Integer.toString(efSearch));
   jdbc.queryForObject("SELECT set_config('hnsw.iterative_scan', 'strict_order', true)",String.class);
   return query.get();
  });
 }
}
