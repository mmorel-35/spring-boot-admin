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

package de.codecentric.boot.admin.server.web.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.servlet.HandlerMapping;
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

	private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();

	private final PathMatcher pathMatcher = new AntPathMatcher();

	private final InstanceWebProxy instanceWebProxy;

	private final HttpHeaderFilter httpHeadersFilter;

	private final InstanceRegistry registry;

	private final String adminContextPath;

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

	@ResponseBody
	@RequestMapping(path = INSTANCE_MAPPED_PATH, method = { RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST,
			RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS })
	public void instanceProxy(@PathVariable("instanceId") String instanceId, HttpServletRequest servletRequest) {
		// start async because we will commit from different thread.
		// otherwise incorrect thread local objects (session and security context) will be
		// stored.
		// check for example
		// org.springframework.security.web.context.HttpSessionSecurityContextRepository.SaveToSessionRequestWrapper.startAsync()
		AsyncContext asyncContext = servletRequest.startAsync();
		asyncContext.setTimeout(-1); // no timeout because instanceWebProxy will handle it
		// for us
		try {
			ServletServerHttpRequest request = new ServletServerHttpRequest(
					(HttpServletRequest) asyncContext.getRequest());
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
					ServerHttpResponse response = new ServletServerHttpResponse(
							(HttpServletResponse) asyncContext.getResponse());
					response.setStatusCode(HttpStatusCode.valueOf(entry.getStatusCode()));
					response.getHeaders().addAll(entry.getHttpHeaders());
					try {
						OutputStream responseBody = response.getBody();
						response.flush();
						responseBody.write(entry.getBody());
						responseBody.flush();
					}
					catch (IOException ex) {
						throw new RuntimeException(ex);
					}
					return;
				}
				log.trace("Cache miss for instance {} endpoint '{}'", instanceId, endpointId);
			}

			Flux<DataBuffer> requestBody = DataBufferUtils.readInputStream(request::getBody, this.bufferFactory, 4096);
			InstanceWebProxy.ForwardRequest fwdRequest = createForwardRequest(request, requestBody, localPath,
					rawQuery);

			boolean fillCache = this.responseCache != null
					&& this.responseCache.shouldCache(request.getMethod(), endpointId);

			this.instanceWebProxy
				.forward(this.registry.getInstance(InstanceId.of(instanceId)), fwdRequest, (clientResponse) -> {
					HttpStatusCode statusCode = clientResponse.statusCode();
					HttpHeaders filteredHeaders = this.httpHeadersFilter
						.filterHeaders(clientResponse.headers().asHttpHeaders());
					ServerHttpResponse response = new ServletServerHttpResponse(
							(HttpServletResponse) asyncContext.getResponse());
					response.setStatusCode(statusCode);
					response.getHeaders().addAll(filteredHeaders);
					try {
						OutputStream responseBody = response.getBody();
						response.flush();
						if (fillCache && statusCode.is2xxSuccessful()) {
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
										log.trace("Cached response for instance {} endpoint '{}' ({} bytes)",
												instanceId, endpointId, bytes.length);
									}
									return writeAndFlush(Flux.just(this.bufferFactory.wrap(bytes)), responseBody);
								});
						}
						return clientResponse.body(BodyExtractors.toDataBuffers())
							.window(1)
							.concatMap((body) -> writeAndFlush(body, responseBody))
							.then();
					}
					catch (IOException ex) {
						return Mono.error(ex);
					}
				})
				// We need to explicitly block so the headers are received and written
				// before any async dispatch otherwise the FrameworkServlet will add
				// wrong
				// Allow header for OPTIONS request
				.block();
		}
		finally {
			asyncContext.complete();
		}
	}

	@ResponseBody
	@RequestMapping(path = APPLICATION_MAPPED_PATH, method = { RequestMethod.GET, RequestMethod.HEAD,
			RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS })
	public Flux<InstanceWebProxy.InstanceResponse> endpointProxy(
			@PathVariable("applicationName") String applicationName, HttpServletRequest servletRequest) {

		ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
		Flux<DataBuffer> cachedBody = DataBufferUtils.readInputStream(request::getBody, this.bufferFactory, 4096)
			.cache();

		InstanceWebProxy.ForwardRequest fwdRequest = createForwardRequest(request, cachedBody,
				this.adminContextPath + APPLICATION_MAPPED_PATH);
		return this.instanceWebProxy.forward(this.registry.getInstances(applicationName), fwdRequest);
	}

	private InstanceWebProxy.ForwardRequest createForwardRequest(ServletServerHttpRequest request,
			Flux<DataBuffer> body, String pathPattern) {
		return createForwardRequest(request, body, getLocalPath(pathPattern, request), request.getURI().getRawQuery());
	}

	private InstanceWebProxy.ForwardRequest createForwardRequest(ServletServerHttpRequest request,
			Flux<DataBuffer> body, String localPath, @Nullable String rawQuery) {
		URI uri = UriComponentsBuilder.fromPath(localPath).query(rawQuery).build(true).toUri();
		return InstanceWebProxy.ForwardRequest.builder()
			.uri(uri)
			.method(request.getMethod())
			.headers(this.httpHeadersFilter.filterHeaders(request.getHeaders()))
			.body(BodyInserters.fromDataBuffers(body))
			.build();
	}

	private String getLocalPath(String pathPattern, ServletServerHttpRequest request) {
		String pathWithinApplication = request.getServletRequest()
			.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE)
			.toString();
		return this.pathMatcher.extractPathWithinPattern(pathPattern, pathWithinApplication);
	}

	private Mono<Void> writeAndFlush(Flux<DataBuffer> body, OutputStream responseBody) {
		return DataBufferUtils.write(body, responseBody).map(DataBufferUtils::release).then(Mono.create((sink) -> {
			try {
				responseBody.flush();
				sink.success();
			}
			catch (IOException ex) {
				sink.error(ex);
			}
		}));
	}

	private static String extractEndpointId(String localPath) {
		int slash = localPath.indexOf('/');
		return (slash > 0) ? localPath.substring(0, slash) : localPath;
	}

}
