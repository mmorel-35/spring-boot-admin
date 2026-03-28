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

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2ReactiveHttpHeadersProviderTest {

	private final ReactiveOAuth2AuthorizedClientManager authorizedClientManager = mock(
			ReactiveOAuth2AuthorizedClientManager.class);

	// ---------------------------------------------------------------------------
	// Tests for the provider itself (resolver → Bearer token header)
	// ---------------------------------------------------------------------------

	@Test
	void whenResolverReturnsId_returnsAuthorizationHeader() {
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("my-client", "my-token");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				(instance) -> "my-client");

		StepVerifier.create(provider.getHeaders(buildInstance("any-service")))
			.assertNext(
					(headers) -> assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer my-token"))
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("my-client");
	}

	@Test
	void whenResolverReturnsNull_returnsEmptyHeaders() {
		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				(instance) -> null);

		StepVerifier.create(provider.getHeaders(buildInstance("any-service")))
			.assertNext((headers) -> assertThat(headers.toSingleValueMap()).isEmpty())
			.verifyComplete();
	}

	@Test
	void whenManagerReturnsEmpty_returnsEmptyHeaders() {
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(Mono.empty());

		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				(instance) -> "my-client");

		StepVerifier.create(provider.getHeaders(buildInstance("any-service")))
			.assertNext((headers) -> assertThat(headers.toSingleValueMap()).isEmpty())
			.verifyComplete();
	}

	// ---------------------------------------------------------------------------
	// Tests for metadataRegistrationIdResolver()
	// ---------------------------------------------------------------------------

	@Test
	void metadataResolver_readsDotKey() {
		Instance instance = buildInstanceWithMetadata("svc", "oauth2.registration-id", "dot-client");
		assertThat(OAuth2ReactiveHttpHeadersProvider.metadataRegistrationIdResolver().resolve(instance))
			.isEqualTo("dot-client");
	}

	@Test
	void metadataResolver_readsDashKey() {
		Instance instance = buildInstanceWithMetadata("svc", "oauth2-registration-id", "dash-client");
		assertThat(OAuth2ReactiveHttpHeadersProvider.metadataRegistrationIdResolver().resolve(instance))
			.isEqualTo("dash-client");
	}

	@Test
	void metadataResolver_returnsNullWhenNoMetadata() {
		Instance instance = buildInstance("svc");
		assertThat(OAuth2ReactiveHttpHeadersProvider.metadataRegistrationIdResolver().resolve(instance)).isNull();
	}

	// ---------------------------------------------------------------------------
	// Tests for resolver chains composed with andThen()
	// ---------------------------------------------------------------------------

	@Test
	void metadataRegistrationIdTakesPrecedenceOverServiceMapAndDefault() {
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("metadata-client", "metadata-token");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		Map<String, String> serviceMap = Collections.singletonMap("my-service", "service-client");
		String defaultId = "default-client";
		OAuth2RegistrationIdResolver resolver = chainResolver(defaultId, serviceMap);
		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				resolver);

		Instance instance = buildInstanceWithMetadata("my-service", "oauth2.registration-id", "metadata-client");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext((headers) -> assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer metadata-token"))
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("metadata-client");
	}

	@Test
	void serviceSpecificRegistrationIdTakesPrecedenceOverDefault() {
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("service-client", "service-token");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		Map<String, String> serviceMap = Collections.singletonMap("my-service", "service-client");
		OAuth2RegistrationIdResolver resolver = chainResolver("default-client", serviceMap);
		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				resolver);

		Instance instance = buildInstance("my-service");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext((headers) -> assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer service-token"))
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("service-client");
	}

	@Test
	void defaultRegistrationIdIsUsed_whenNoServiceOverrideExists() {
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("default-client", "default-token");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		OAuth2RegistrationIdResolver resolver = chainResolver("default-client", Collections.emptyMap());
		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				resolver);

		Instance instance = buildInstance("some-service");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext((headers) -> assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer default-token"))
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("default-client");
	}

	@Test
	void emptyHeadersReturnedWhenNoRegistrationIdConfigured() {
		OAuth2RegistrationIdResolver resolver = chainResolver(null, Collections.emptyMap());
		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				resolver);

		StepVerifier.create(provider.getHeaders(buildInstance("some-service")))
			.assertNext((headers) -> assertThat(headers.toSingleValueMap()).isEmpty())
			.verifyComplete();
	}

	@Test
	void customResolverCanIgnoreMetadata() {
		// A fixed resolver that never checks metadata — simulates an operator-controlled
		// trust boundary (replaces the old allowMetadataOverride=false flag)
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("fixed-client", "fixed-token");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		OAuth2RegistrationIdResolver fixedResolver = (instance) -> "fixed-client";
		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				fixedResolver);

		// instance has metadata claiming a different registration ID
		Instance instance = buildInstanceWithMetadata("svc", "oauth2.registration-id", "metadata-client");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext((headers) -> assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer fixed-token"))
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		// metadata-client must NOT have been used
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("fixed-client");
	}

	// ---------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------

	/**
	 * Builds a resolver chain that mirrors the default auto-configuration: metadata →
	 * serviceMap → defaultId.
	 * @param defaultId the fallback registration ID (may be null)
	 * @param serviceMap per-service registration ID overrides
	 * @return a chained resolver
	 */
	private static OAuth2RegistrationIdResolver chainResolver(String defaultId, Map<String, String> serviceMap) {
		return OAuth2ReactiveHttpHeadersProvider.metadataRegistrationIdResolver()
			.andThen((instance) -> serviceMap.get(instance.getRegistration().getName()))
			.andThen((instance) -> defaultId);
	}

	private static Instance buildInstance(String serviceName) {
		Registration registration = Registration.create(serviceName, "https://health").name(serviceName).build();
		return Instance.create(InstanceId.of("id")).register(registration);
	}

	private static Instance buildInstanceWithMetadata(String serviceName, String metadataKey, String metadataValue) {
		Registration registration = Registration.create(serviceName, "https://health")
			.name(serviceName)
			.metadata(metadataKey, metadataValue)
			.build();
		return Instance.create(InstanceId.of("id")).register(registration);
	}

	private static OAuth2AuthorizedClient buildAuthorizedClient(String registrationId, String tokenValue) {
		ClientRegistration clientRegistration = ClientRegistration.withRegistrationId(registrationId)
			.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
			.clientId("client-id")
			.tokenUri("https://token-uri")
			.build();
		OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokenValue,
				Instant.now(), Instant.now().plusSeconds(300));
		return new OAuth2AuthorizedClient(clientRegistration, "spring-boot-admin-server", accessToken);
	}

}
