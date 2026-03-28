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
 * The registration ID is determined by the supplied {@link OAuth2RegistrationIdResolver}.
 * The default resolver reads the ID from the instance metadata keys
 * {@code "oauth2.registration-id"} / {@code "oauth2-registration-id"} (see
 * {@link #metadataRegistrationIdResolver()}). More complex chains — falling back to a
 * service-map or a global default — are composed using
 * {@link OAuth2RegistrationIdResolver#andThen(OAuth2RegistrationIdResolver)}.
 *
 * <p>
 * <strong>Trust model:</strong> When the default resolver chain includes metadata-based
 * resolution, instance metadata is controlled by the registering client. In a
 * multi-tenant or untrusted-registration environment a malicious client could set the
 * {@code oauth2.registration-id} metadata key to any value, causing the server to use a
 * different OAuth2 client registration when polling that instance. To prevent this,
 * supply a custom {@link OAuth2RegistrationIdResolver} bean that does not inspect
 * instance metadata. Auto-configuration exposes the default resolver as a
 * {@code @ConditionalOnMissingBean} so that a replacement can be wired in without
 * modifying any other component.
 */
public class OAuth2ReactiveHttpHeadersProvider implements ReactiveHttpHeadersProvider {

	private static final String[] REGISTRATION_ID_KEYS = { "oauth2.registration-id", "oauth2-registration-id" };

	private static final Authentication SBA_SERVER_PRINCIPAL = new AnonymousAuthenticationToken(
			"spring-boot-admin-server", "spring-boot-admin-server",
			AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	private final ReactiveOAuth2AuthorizedClientManager authorizedClientManager;

	private final OAuth2RegistrationIdResolver registrationIdResolver;

	/**
	 * Creates a provider that resolves the registration ID from instance metadata only
	 * (using {@link #metadataRegistrationIdResolver()}).
	 * @param authorizedClientManager the manager used to obtain Bearer tokens
	 */
	public OAuth2ReactiveHttpHeadersProvider(ReactiveOAuth2AuthorizedClientManager authorizedClientManager) {
		this(authorizedClientManager, metadataRegistrationIdResolver());
	}

	/**
	 * Creates a provider that uses the given {@link OAuth2RegistrationIdResolver} to
	 * determine the registration ID per instance.
	 * @param authorizedClientManager the manager used to obtain Bearer tokens
	 * @param registrationIdResolver the resolver that maps an instance to a registration
	 * ID
	 */
	public OAuth2ReactiveHttpHeadersProvider(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
			OAuth2RegistrationIdResolver registrationIdResolver) {
		this.authorizedClientManager = authorizedClientManager;
		this.registrationIdResolver = registrationIdResolver;
	}

	/**
	 * Returns an {@link OAuth2RegistrationIdResolver} that reads the registration ID from
	 * the instance metadata keys {@code "oauth2.registration-id"} and
	 * {@code "oauth2-registration-id"}.
	 * @return a metadata-based resolver
	 */
	public static OAuth2RegistrationIdResolver metadataRegistrationIdResolver() {
		return (instance) -> getMetadataValue(instance, REGISTRATION_ID_KEYS);
	}

	@Override
	public Mono<HttpHeaders> getHeaders(Instance instance) {
		String registrationId = this.registrationIdResolver.resolve(instance);
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
