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

package de.codecentric.boot.admin.server.web;

import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.web.cache.ActuatorResponseCache;
import de.codecentric.boot.admin.server.web.cache.CacheEntry;

/**
 * Decorator over {@link InstanceWebProxy} that adds transparent server-side response
 * caching for single-instance actuator proxy calls.
 *
 * <p>
 * On a GET request for a configured cacheable endpoint:
 * <ol>
 * <li>If a valid cache entry exists it is returned without touching upstream.</li>
 * <li>On a cache miss the response is forwarded normally; a 2xx body within the
 * configured size limit is stored for subsequent requests.</li>
 * </ol>
 *
 * <p>
 * On a mutating request (POST, PUT, PATCH, DELETE) that returns 2xx, the endpoint's
 * cached entries are invalidated so the next GET receives fresh data.
 *
 * <p>
 * Application-level proxy calls ({@link #forward(Flux, InstanceWebProxy.ForwardRequest)})
 * are never cached because they fan out to multiple instances.
 *
 * <p>
 * When no {@link ActuatorResponseCache} is configured all calls delegate directly to the
 * wrapped {@link InstanceWebProxy}.
 */
public class CachingInstanceWebProxy {

	private static final Logger log = LoggerFactory.getLogger(CachingInstanceWebProxy.class);

	private final InstanceWebProxy delegate;

	@Nullable private final ActuatorResponseCache cache;

	private final HttpHeaderFilter headerFilter;

	private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();

	private final ExchangeStrategies strategies = ExchangeStrategies.withDefaults();

	public CachingInstanceWebProxy(InstanceWebProxy delegate, @Nullable ActuatorResponseCache cache,
			HttpHeaderFilter headerFilter) {
		this.delegate = delegate;
		this.cache = cache;
		this.headerFilter = headerFilter;
	}

	/**
	 * Forwards a request to a single instance, applying cache semantics when a cache is
	 * configured.
	 * @param instanceId identity of the target instance (used as the cache key prefix)
	 * @param instanceMono reactive lookup of the target {@link Instance}
	 * @param forwardRequest the request to proxy
	 * @param responseHandler consumer of the (possibly cached) {@link ClientResponse}
	 * @param <V> response type produced by {@code responseHandler}
	 * @return result of {@code responseHandler}
	 */
	public <V> Mono<V> forward(InstanceId instanceId, Mono<Instance> instanceMono,
			InstanceWebProxy.ForwardRequest forwardRequest, Function<ClientResponse, Mono<V>> responseHandler) {
		ActuatorResponseCache activeCache = this.cache;
		if (activeCache == null) {
			return this.delegate.forward(instanceMono, forwardRequest, responseHandler);
		}

		String endpointPath = forwardRequest.getUri().getPath();
		String rawQuery = forwardRequest.getUri().getRawQuery();
		HttpMethod method = forwardRequest.getMethod();
		String endpointId = extractEndpointId(endpointPath);

		Optional<CacheEntry> hit = lookupCache(activeCache, instanceId, endpointPath, rawQuery, method, endpointId);
		if (hit.isPresent()) {
			return responseHandler.apply(buildClientResponse(hit.get()));
		}

		return this.delegate.forward(instanceMono, forwardRequest, (cr) -> interceptResponse(activeCache, cr,
				instanceId, endpointPath, rawQuery, endpointId, method, responseHandler));
	}

	/**
	 * Forwards a request to all instances of an application. Caching is never applied to
	 * fan-out calls.
	 * @param instances the instances to forward to
	 * @param forwardRequest the request to proxy
	 * @return a stream of per-instance responses
	 */
	public Flux<InstanceWebProxy.InstanceResponse> forward(Flux<Instance> instances,
			InstanceWebProxy.ForwardRequest forwardRequest) {
		return this.delegate.forward(instances, forwardRequest);
	}

	// ---- cache interaction ---------------------------------------------------

	private Optional<CacheEntry> lookupCache(ActuatorResponseCache cache, InstanceId instanceId, String endpointPath,
			@Nullable String rawQuery, HttpMethod method, String endpointId) {
		if (!cache.shouldCache(method, endpointId)) {
			return Optional.empty();
		}
		Optional<CacheEntry> entry = cache.get(instanceId, endpointPath, rawQuery);
		if (entry.isPresent()) {
			log.trace("Cache hit for instance {} endpoint '{}'", instanceId, endpointId);
		}
		else {
			log.trace("Cache miss for instance {} endpoint '{}'", instanceId, endpointId);
		}
		return entry;
	}

	private <V> Mono<V> interceptResponse(ActuatorResponseCache cache, ClientResponse clientResponse,
			InstanceId instanceId, String endpointPath, @Nullable String rawQuery, String endpointId, HttpMethod method,
			Function<ClientResponse, Mono<V>> responseHandler) {
		HttpStatusCode statusCode = clientResponse.statusCode();

		if (isMutatingMethod(method) && statusCode.is2xxSuccessful() && cache.shouldCache(HttpMethod.GET, endpointId)) {
			cache.invalidateEndpointForInstance(instanceId, endpointId);
		}

		if (cache.shouldCache(method, endpointId) && statusCode.is2xxSuccessful()) {
			return bufferAndCache(cache, clientResponse, instanceId, endpointPath, rawQuery, endpointId, statusCode,
					responseHandler);
		}

		return responseHandler.apply(clientResponse);
	}

	private <V> Mono<V> bufferAndCache(ActuatorResponseCache cache, ClientResponse clientResponse,
			InstanceId instanceId, String endpointPath, @Nullable String rawQuery, String endpointId,
			HttpStatusCode statusCode, Function<ClientResponse, Mono<V>> responseHandler) {
		HttpHeaders originalHeaders = clientResponse.headers().asHttpHeaders();
		return DataBufferUtils.join(clientResponse.body(BodyExtractors.toDataBuffers()))
			.switchIfEmpty(Mono.fromSupplier(() -> this.bufferFactory.allocateBuffer(0)))
			.flatMap((joined) -> {
				byte[] bytes = new byte[joined.readableByteCount()];
				joined.read(bytes);
				DataBufferUtils.release(joined);
				if (bytes.length <= cache.getMaxPayloadSize()) {
					HttpHeaders filteredHeaders = this.headerFilter.filterHeaders(originalHeaders);
					cache.put(instanceId, endpointPath, rawQuery,
							new CacheEntry(statusCode.value(), filteredHeaders, bytes));
					log.trace("Cached response for endpoint '{}' ({} bytes)", endpointId, bytes.length);
				}
				return responseHandler.apply(rebuildClientResponse(statusCode, originalHeaders, bytes));
			});
	}

	// ---- ClientResponse builders --------------------------------------------

	/**
	 * Reconstructs a {@link ClientResponse} from a stored {@link CacheEntry}. The entry
	 * already contains filtered headers.
	 * @param entry the cached response entry
	 * @return a {@link ClientResponse} backed by the cached body bytes
	 */
	private ClientResponse buildClientResponse(CacheEntry entry) {
		DataBuffer body = this.bufferFactory.wrap(entry.getBody());
		return ClientResponse.create(HttpStatusCode.valueOf(entry.getStatusCode()), this.strategies)
			.headers((h) -> h.addAll(entry.getHttpHeaders()))
			.body(Flux.just(body))
			.build();
	}

	/**
	 * Reconstructs a {@link ClientResponse} after body buffering so the
	 * {@code responseHandler} can consume the buffered bytes. The original (unfiltered)
	 * headers are preserved so that the handler can apply its own filtering.
	 * @param statusCode the upstream response status
	 * @param originalHeaders the unfiltered upstream response headers
	 * @param bytes the buffered response body
	 * @return a {@link ClientResponse} backed by the buffered bytes
	 */
	private ClientResponse rebuildClientResponse(HttpStatusCode statusCode, HttpHeaders originalHeaders, byte[] bytes) {
		DataBuffer body = this.bufferFactory.wrap(bytes);
		return ClientResponse.create(statusCode, this.strategies)
			.headers((h) -> h.addAll(originalHeaders))
			.body(Flux.just(body))
			.build();
	}

	// ---- static helpers ------------------------------------------------------

	private static String extractEndpointId(String endpointPath) {
		int slash = endpointPath.indexOf('/');
		return (slash > 0) ? endpointPath.substring(0, slash) : endpointPath;
	}

	private static boolean isMutatingMethod(HttpMethod method) {
		return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method) || HttpMethod.PATCH.equals(method)
				|| HttpMethod.DELETE.equals(method);
	}

}
