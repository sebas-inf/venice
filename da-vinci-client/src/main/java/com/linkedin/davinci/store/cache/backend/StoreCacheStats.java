package com.linkedin.davinci.store.cache.backend;

import com.linkedin.davinci.store.cache.VeniceStoreCache;
import com.linkedin.venice.stats.AbstractVeniceStats;
import com.linkedin.venice.stats.Gauge;
import com.linkedin.venice.stats.TehutiUtils;
import io.tehuti.metrics.MetricsRepository;
import io.tehuti.metrics.Sensor;
import io.tehuti.metrics.stats.Count;
import io.tehuti.metrics.stats.OccurrenceRate;
import io.tehuti.metrics.stats.Rate;


public class StoreCacheStats extends AbstractVeniceStats {

  private final Sensor cacheHitRate;
  private final Sensor cacheMissCount;
  private final Sensor cacheHitCount;
  private VeniceStoreCache servingCache;

  public StoreCacheStats(MetricsRepository metricsRepository, String name) {
    super(metricsRepository, name);
    cacheHitCount = registerSensor("cache_hit", new Gauge(this::getHitCount));
    cacheMissCount = registerSensor("cache_miss", new Gauge(this::getMissCount));
    cacheHitRate = registerSensor("cache_hit_rate", new Gauge(this::getHitRate));
  }

  public synchronized void registerServingCache(VeniceStoreCache cache) {
    servingCache = cache;
  }

  public synchronized long getHitCount() {
    return servingCache == null ? 0 : servingCache.hitCount();
  }

  public synchronized long getMissCount() {
    return servingCache == null ? 0 : servingCache.missCount();
  }

  public synchronized double getHitRate() {
    return servingCache == null ? 0 : servingCache.hitRate();
  }
}
