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

	@Test
	void metadataRegistrationIdTakesPrecedenceOverServiceMapAndDefault() {
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("metadata-client", "metadata-token");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				"default-client", Collections.singletonMap("my-service", "service-client"));

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
	void metadataRegistrationIdWithDashKeyIsAccepted() {
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("dash-client", "dash-token");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				"default-client", Collections.emptyMap());

		Instance instance = buildInstanceWithMetadata("some-service", "oauth2-registration-id", "dash-client");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext(
					(headers) -> assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer dash-token"))
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("dash-client");
	}

	@Test
	void defaultRegistrationIdIsUsed_whenNoServiceOverrideExists() {
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("default-client", "test-token-value");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				"default-client", Collections.emptyMap());

		Instance instance = buildInstance("some-service");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext((headers) -> assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer test-token-value"))
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("default-client");
	}

	@Test
	void serviceSpecificRegistrationIdTakesPrecedenceOverDefault() {
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("service-client", "service-token-value");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				"default-client", Collections.singletonMap("my-service", "service-client"));

		Instance instance = buildInstance("my-service");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext((headers) -> assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer service-token-value"))
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("service-client");
	}

	@Test
	void emptyHeadersReturnedWhenNoRegistrationIdConfigured() {
		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				null, Collections.emptyMap());

		Instance instance = buildInstance("some-service");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext((headers) -> assertThat(headers.toSingleValueMap()).isEmpty())
			.verifyComplete();
	}

	@Test
	void emptyHeadersReturnedWhenAuthorizedClientManagerReturnsEmpty() {
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(Mono.empty());

		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				"default-client", Collections.emptyMap());

		Instance instance = buildInstance("some-service");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext((headers) -> assertThat(headers.toSingleValueMap()).isEmpty())
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("default-client");
	}

	@Test
	void serviceMapOverrideIsUsed_whenRegistered() {
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("specific-client", "specific-token");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		Map<String, String> serviceMap = Collections.singletonMap("target-service", "specific-client");
		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				null, serviceMap);

		Instance instance = buildInstance("target-service");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext((headers) -> assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer specific-token"))
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("specific-client");
	}

	@Test
	void metadataRegistrationIdIgnored_whenAllowMetadataOverrideIsFalse() {
		OAuth2AuthorizedClient authorizedClient = buildAuthorizedClient("default-client", "default-token");
		when(this.authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
			.thenReturn(Mono.just(authorizedClient));

		OAuth2ReactiveHttpHeadersProvider provider = new OAuth2ReactiveHttpHeadersProvider(this.authorizedClientManager,
				"default-client", Collections.emptyMap(), false);

		Instance instance = buildInstanceWithMetadata("some-service", "oauth2.registration-id", "metadata-client");

		StepVerifier.create(provider.getHeaders(instance))
			.assertNext((headers) -> assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION))
				.isEqualTo("Bearer default-token"))
			.verifyComplete();

		ArgumentCaptor<OAuth2AuthorizeRequest> captor = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.authorizedClientManager).authorize(captor.capture());
		// metadata-client must NOT be used; default-client is used instead
		assertThat(captor.getValue().getClientRegistrationId()).isEqualTo("default-client");
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
