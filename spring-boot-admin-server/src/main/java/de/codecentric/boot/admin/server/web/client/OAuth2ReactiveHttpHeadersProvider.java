/*
 * Copyright 2014-2024 the original author or authors.
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

package de.codecentric.boot.admin.server.web.client;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.web.client.reactive.ReactiveHttpHeadersProvider;

/**
 * {@link ReactiveHttpHeadersProvider} that injects a Bearer token obtained via the OAuth2
 * Client Credentials flow. The token is fetched (and cached/refreshed) by the provided
 * {@link ReactiveOAuth2AuthorizedClientManager}.
 *
 * <p>
 * Resolution order for the registration ID:
 * <ol>
 * <li>Instance metadata key {@code "oauth2.registration-id"} (also accepted:
 * {@code "oauth2-registration-id"})</li>
 * <li>Per-service override via {@code serviceRegistrationMap} (keyed by
 * {@link de.codecentric.boot.admin.server.domain.values.Registration#getName()})</li>
 * <li>Default registration ID</li>
 * <li>No-op (returns empty headers) if none of the above is configured</li>
 * </ol>
 */
public class OAuth2ReactiveHttpHeadersProvider implements ReactiveHttpHeadersProvider {

	private static final String[] REGISTRATION_ID_KEYS = { "oauth2.registration-id", "oauth2-registration-id" };

	private static final Authentication SBA_SERVER_PRINCIPAL = new AnonymousAuthenticationToken(
			"spring-boot-admin-server", "spring-boot-admin-server",
			AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	private final ReactiveOAuth2AuthorizedClientManager authorizedClientManager;

	@Nullable private final String defaultRegistrationId;

	private final Map<String, String> serviceRegistrationMap;

	public OAuth2ReactiveHttpHeadersProvider(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
			@Nullable String defaultRegistrationId, Map<String, String> serviceRegistrationMap) {
		this.authorizedClientManager = authorizedClientManager;
		this.defaultRegistrationId = defaultRegistrationId;
		this.serviceRegistrationMap = serviceRegistrationMap;
	}

	@Override
	public Mono<HttpHeaders> getHeaders(Instance instance) {
		String registrationId = resolveRegistrationId(instance);
		if (registrationId == null) {
			return Mono.just(HttpHeaders.EMPTY);
		}
		var request = OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
			.principal(SBA_SERVER_PRINCIPAL)
			.build();
		return this.authorizedClientManager.authorize(request).flatMap((authorizedClient) -> {
			OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
			if (accessToken == null) {
				return Mono.just(HttpHeaders.EMPTY);
			}
			HttpHeaders headers = new HttpHeaders();
			headers.setBearerAuth(accessToken.getTokenValue());
			return Mono.just(headers);
		}).defaultIfEmpty(HttpHeaders.EMPTY);
	}

	@Nullable private String resolveRegistrationId(Instance instance) {
		String fromMetadata = getMetadataValue(instance, REGISTRATION_ID_KEYS);
		if (fromMetadata != null) {
			return fromMetadata;
		}
		String serviceName = instance.getRegistration().getName();
		String fromServiceMap = this.serviceRegistrationMap.get(serviceName);
		if (StringUtils.hasText(fromServiceMap)) {
			return fromServiceMap;
		}
		return StringUtils.hasText(this.defaultRegistrationId) ? this.defaultRegistrationId : null;
	}

	@Nullable private static String getMetadataValue(Instance instance, String[] keys) {
		Map<String, String> metadata = instance.getRegistration().getMetadata();
		for (String key : keys) {
			String value = metadata.get(key);
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

}
