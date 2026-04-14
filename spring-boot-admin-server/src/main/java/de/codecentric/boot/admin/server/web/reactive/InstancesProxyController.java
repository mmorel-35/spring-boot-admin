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

package de.codecentric.boot.admin.server.web.reactive;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

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
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import de.codecentric.boot.admin.server.web.AdminController;
import de.codecentric.boot.admin.server.web.HttpHeaderFilter;
import de.codecentric.boot.admin.server.web.InstanceWebProxy;
import de.codecentric.boot.admin.server.web.cache.ActuatorResponseCache;
import de.codecentric.boot.admin.server.web.cache.CacheEntry;
import de.codecentric.boot.admin.server.web.client.InstanceWebClient;

/**
 * Http Handler for proxied requests
 */
@AdminController
public class InstancesProxyController {

	private static final Logger log = LoggerFactory.getLogger(InstancesProxyController.class);

	private static final String INSTANCE_MAPPED_PATH = "/instances/{instanceId}/actuator/**";

	private static final String APPLICATION_MAPPED_PATH = "/applications/{applicationName}/actuator/**";

	private final PathMatcher pathMatcher = new AntPathMatcher();

	private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();

	private final InstanceRegistry registry;

	private final InstanceWebProxy instanceWebProxy;

	private final String adminContextPath;

	private final HttpHeaderFilter httpHeadersFilter;

	@Nullable private final ActuatorResponseCache responseCache;

	public InstancesProxyController(String adminContextPath, Set<String> ignoredHeaders, InstanceRegistry registry,
			InstanceWebClient instanceWebClient) {
		this(adminContextPath, ignoredHeaders, registry, instanceWebClient, null);
	}

	public InstancesProxyController(String adminContextPath, Set<String> ignoredHeaders, InstanceRegistry registry,
			InstanceWebClient instanceWebClient, @Nullable ActuatorResponseCache responseCache) {
		this.adminContextPath = adminContextPath;
		this.registry = registry;
		this.httpHeadersFilter = new HttpHeaderFilter(ignoredHeaders);
		this.instanceWebProxy = new InstanceWebProxy(instanceWebClient);
		this.responseCache = responseCache;
	}

	@RequestMapping(path = INSTANCE_MAPPED_PATH, method = { RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST,
			RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS })
	public Mono<Void> endpointProxy(@PathVariable("instanceId") String instanceId, ServerHttpRequest request,
			ServerHttpResponse response) {
		String localPath = getLocalPath(this.adminContextPath + INSTANCE_MAPPED_PATH, request);
		String rawQuery = request.getURI().getRawQuery();
		String endpointId = extractEndpointId(localPath);

		// Serve from cache when possible (GET only, configured endpoints, cache hit)
		if (this.responseCache != null && this.responseCache.shouldCache(request.getMethod(), endpointId)) {
			InstanceId id = InstanceId.of(instanceId);
			Optional<CacheEntry> cached = this.responseCache.get(id, localPath, rawQuery);
			if (cached.isPresent()) {
				log.trace("Cache hit for instance {} endpoint '{}'", instanceId, endpointId);
				CacheEntry entry = cached.get();
				response.setStatusCode(HttpStatusCode.valueOf(entry.getStatusCode()));
				response.getHeaders().addAll(entry.getHttpHeaders());
				DataBuffer buf = this.bufferFactory.wrap(entry.getBody());
				return response.writeAndFlushWith(Flux.just(Flux.just(buf)));
			}
			log.trace("Cache miss for instance {} endpoint '{}'", instanceId, endpointId);
		}

		InstanceWebProxy.ForwardRequest fwdRequest = createForwardRequest(request, request.getBody(), localPath,
				rawQuery);

		return this.instanceWebProxy.forward(this.registry.getInstance(InstanceId.of(instanceId)), fwdRequest,
				(clientResponse) -> {
					HttpStatusCode statusCode = clientResponse.statusCode();
					HttpHeaders filteredHeaders = this.httpHeadersFilter
						.filterHeaders(clientResponse.headers().asHttpHeaders());
					response.setStatusCode(statusCode);
					response.getHeaders().addAll(filteredHeaders);

					boolean fillCache = this.responseCache != null
							&& this.responseCache.shouldCache(request.getMethod(), endpointId)
							&& statusCode.is2xxSuccessful();

					// After a successful mutating request, evict that endpoint's cache
					// entries
					// so the next GET returns fresh data.
					if (this.responseCache != null && isMutatingMethod(request.getMethod())
							&& statusCode.is2xxSuccessful()
							&& this.responseCache.shouldCache(HttpMethod.GET, endpointId)) {
						this.responseCache.invalidateEndpointForInstance(InstanceId.of(instanceId), endpointId);
					}

					if (fillCache) {
						InstanceId id = InstanceId.of(instanceId);
						return DataBufferUtils.join(clientResponse.body(BodyExtractors.toDataBuffers()))
							.switchIfEmpty(Mono.fromSupplier(() -> this.bufferFactory.allocateBuffer(0)))
							.flatMap((joined) -> {
								byte[] bytes = new byte[joined.readableByteCount()];
								joined.read(bytes);
								DataBufferUtils.release(joined);
								if (bytes.length <= this.responseCache.getMaxPayloadSize()) {
									this.responseCache.put(id, localPath, rawQuery,
											new CacheEntry(statusCode.value(), filteredHeaders, bytes));
									log.trace("Cached response for instance {} endpoint '{}' ({} bytes)", instanceId,
											endpointId, bytes.length);
								}
								return response.writeAndFlushWith(Flux.just(Flux.just(this.bufferFactory.wrap(bytes))));
							});
					}

					return response.writeAndFlushWith(clientResponse.body(BodyExtractors.toDataBuffers()).window(1));
				});
	}

	@ResponseBody
	@RequestMapping(path = APPLICATION_MAPPED_PATH, method = { RequestMethod.GET, RequestMethod.HEAD,
			RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS })
	public Flux<InstanceWebProxy.InstanceResponse> endpointProxy(
			@PathVariable("applicationName") String applicationName, ServerHttpRequest request) {

		Flux<DataBuffer> cachedBody = request.getBody().map((b) -> {
			DataBuffer dataBuffer = this.bufferFactory.allocateBuffer(b.readableByteCount());
			try (var iterator = b.readableByteBuffers()) {
				iterator.forEachRemaining(dataBuffer::write);
			}
			DataBufferUtils.release(b);
			return dataBuffer;
		}).cache();

		InstanceWebProxy.ForwardRequest fwdRequest = createForwardRequest(request, cachedBody,
				this.adminContextPath + APPLICATION_MAPPED_PATH);

		return this.instanceWebProxy.forward(this.registry.getInstances(applicationName), fwdRequest);
	}

	private InstanceWebProxy.ForwardRequest createForwardRequest(ServerHttpRequest request, Flux<DataBuffer> body,
			String pathPattern) {
		return createForwardRequest(request, body, getLocalPath(pathPattern, request), request.getURI().getRawQuery());
	}

	private InstanceWebProxy.ForwardRequest createForwardRequest(ServerHttpRequest request, Flux<DataBuffer> body,
			String localPath, @Nullable String rawQuery) {
		URI uri = UriComponentsBuilder.fromPath(localPath).query(rawQuery).build(true).toUri();
		return InstanceWebProxy.ForwardRequest.builder()
			.uri(uri)
			.method(request.getMethod())
			.headers(this.httpHeadersFilter.filterHeaders(request.getHeaders()))
			.body(BodyInserters.fromDataBuffers(body))
			.build();
	}

	private String getLocalPath(String pathPattern, ServerHttpRequest request) {
		String pathWithinApplication = request.getPath().pathWithinApplication().value();
		return this.pathMatcher.extractPathWithinPattern(pathPattern, pathWithinApplication);
	}

	private static String extractEndpointId(String localPath) {
		int slash = localPath.indexOf('/');
		return (slash > 0) ? localPath.substring(0, slash) : localPath;
	}

	private static boolean isMutatingMethod(HttpMethod method) {
		return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method) || HttpMethod.PATCH.equals(method)
				|| HttpMethod.DELETE.equals(method);
	}

}
