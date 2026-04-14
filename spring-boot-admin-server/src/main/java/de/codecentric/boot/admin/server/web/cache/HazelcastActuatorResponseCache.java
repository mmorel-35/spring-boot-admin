/*
 * Copyright 2014-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.codecentric.boot.admin.server.web.cache;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.hazelcast.map.IMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;

import de.codecentric.boot.admin.server.config.AdminServerProperties.EndpointCacheProperties;
import de.codecentric.boot.admin.server.domain.values.InstanceId;

/**
 * Hazelcast-backed implementation of {@link ActuatorResponseCache} using an {@link IMap}.
 *
 * <p>
 * TTL is set natively per map entry using
 * {@link IMap#put(Object, Object, long, TimeUnit)} so Hazelcast handles eviction
 * automatically across all cluster nodes. This makes the cache naturally shared between
 * multiple SBA instances without requiring any additional synchronization.
 */
public class HazelcastActuatorResponseCache implements ActuatorResponseCache {

	private static final Logger log = LoggerFactory.getLogger(HazelcastActuatorResponseCache.class);

	private final IMap<String, CacheEntry> map;

	private final EndpointCacheProperties properties;

	public HazelcastActuatorResponseCache(IMap<String, CacheEntry> map, EndpointCacheProperties properties) {
		this.map = map;
		this.properties = properties;
	}

	@Override
	public Optional<CacheEntry> get(InstanceId instanceId, String endpointPath, @Nullable String queryString) {
		if (!this.properties.isEnabled()) {
			return Optional.empty();
		}
		String key = buildKey(instanceId, endpointPath, queryString);
		CacheEntry entry = this.map.get(key);
		return Optional.ofNullable(entry);
	}

	@Override
	public void put(InstanceId instanceId, String endpointPath, @Nullable String queryString, CacheEntry entry) {
		if (!this.properties.isEnabled()) {
			return;
		}
		String key = buildKey(instanceId, endpointPath, queryString);
		long ttlMs = getTtlMs(extractEndpointId(endpointPath));
		this.map.put(key, entry, ttlMs, TimeUnit.MILLISECONDS);
		log.trace("Cached entry for key '{}' (TTL {}ms)", key, ttlMs);
	}

	@Override
	public void invalidateAllForInstance(InstanceId instanceId) {
		String prefix = instanceId.getValue() + ":";
		Set<String> keysToRemove = this.map.keySet()
			.stream()
			.filter((k) -> k.startsWith(prefix))
			.collect(Collectors.toSet());
		keysToRemove.forEach(this.map::delete);
		if (!keysToRemove.isEmpty()) {
			log.debug("Invalidated {} Hazelcast cache entries for instance {}", keysToRemove.size(), instanceId);
		}
	}

	@Override
	public void invalidateEndpointForInstance(InstanceId instanceId, String endpointId) {
		String prefix = instanceId.getValue() + ":" + endpointId;
		Set<String> keysToRemove = this.map.keySet()
			.stream()
			.filter((k) -> k.startsWith(prefix))
			.collect(Collectors.toSet());
		keysToRemove.forEach(this.map::delete);
		if (!keysToRemove.isEmpty()) {
			log.debug("Invalidated {} Hazelcast cache entries for instance {} endpoint '{}'", keysToRemove.size(),
					instanceId, endpointId);
		}
	}

	@Override
	public boolean shouldCache(HttpMethod method, String endpointId) {
		return this.properties.isEnabled() && HttpMethod.GET.equals(method)
				&& this.properties.getEndpoints().contains(endpointId);
	}

	@Override
	public long getMaxPayloadSize() {
		return this.properties.getMaxPayloadSize();
	}

	private long getTtlMs(String endpointId) {
		return this.properties.getTtl().getOrDefault(endpointId, this.properties.getDefaultTtl()).toMillis();
	}

	private static String buildKey(InstanceId instanceId, String endpointPath, @Nullable String queryString) {
		return instanceId.getValue() + ":" + endpointPath + ((queryString != null) ? "?" + queryString : "");
	}

	private static String extractEndpointId(String endpointPath) {
		int slash = endpointPath.indexOf('/');
		return (slash > 0) ? endpointPath.substring(0, slash) : endpointPath;
	}

}
