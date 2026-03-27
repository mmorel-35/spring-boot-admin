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

package de.codecentric.boot.admin.client.config;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import de.codecentric.boot.admin.client.registration.RegistrationClient;
import de.codecentric.boot.admin.client.registration.RestClientRegistrationClient;

/**
 * Auto-configuration that replaces the default Basic Auth interceptor with an OAuth2
 * Client Credentials interceptor when both:
 * <ul>
 * <li>{@code spring-security-oauth2-client} is on the classpath</li>
 * <li>an {@link OAuth2AuthorizedClientManager} bean is present in the context</li>
 * <li>{@code spring.boot.admin.client.oauth2-client-registration-id} is set</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OAuth2AuthorizedClientManager.class)
@ConditionalOnBean(OAuth2AuthorizedClientManager.class)
@AutoConfigureAfter(SpringBootAdminClientAutoConfiguration.class)
public class SpringBootAdminClientOAuth2AutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(RegistrationClient.class)
	public RegistrationClient oauth2RegistrationClient(ClientProperties client, RestClient.Builder restClientBuilder,
			OAuth2AuthorizedClientManager authorizedClientManager) {
		var interceptor = new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
		interceptor.setClientRegistrationIdResolver((request) -> client.getOauth2ClientRegistrationId());
		restClientBuilder.requestInterceptor(interceptor);
		return new RestClientRegistrationClient(restClientBuilder.build());
	}

}
