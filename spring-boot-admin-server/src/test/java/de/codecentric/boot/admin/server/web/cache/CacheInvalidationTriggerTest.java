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

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Sinks;

import de.codecentric.boot.admin.server.config.AdminServerProperties.EndpointCacheProperties;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceEndpointsDetectedEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegistrationUpdatedEvent;
import de.codecentric.boot.admin.server.domain.values.Endpoints;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheInvalidationTriggerTest {

	private Sinks.Many<InstanceEvent> eventSink;

	private InMemoryActuatorResponseCache cache;

	private CacheInvalidationTrigger trigger;

	@BeforeEach
	void setup() {
		EndpointCacheProperties props = new EndpointCacheProperties();
		props.setDefaultTtl(Duration.ofMinutes(5));
		this.cache = new InMemoryActuatorResponseCache(props);
		this.eventSink = Sinks.many().multicast().directBestEffort();
		this.trigger = new CacheInvalidationTrigger(this.eventSink.asFlux(), this.cache);
		this.trigger.start();
	}

	@Test
	void should_invalidate_on_deregistration() throws InterruptedException {
		InstanceId id = InstanceId.of("id1");
		this.cache.put(id, "mappings", null, new CacheEntry(200, new HttpHeaders(), new byte[0]));

		this.eventSink.tryEmitNext(new InstanceDeregisteredEvent(id, 1L));
		Thread.sleep(100);

		assertThat(this.cache.get(id, "mappings", null)).isEmpty();
	}

	@Test
	void should_invalidate_on_registration_update() throws InterruptedException {
		InstanceId id = InstanceId.of("id2");
		Registration reg = Registration.create("app", "http://localhost/mgmt").build();
		this.cache.put(id, "beans", null, new CacheEntry(200, new HttpHeaders(), new byte[0]));

		this.eventSink.tryEmitNext(new InstanceRegistrationUpdatedEvent(id, 1L, reg));
		Thread.sleep(100);

		assertThat(this.cache.get(id, "beans", null)).isEmpty();
	}

	@Test
	void should_invalidate_on_endpoints_detected() throws InterruptedException {
		InstanceId id = InstanceId.of("id3");
		this.cache.put(id, "configprops", null, new CacheEntry(200, new HttpHeaders(), new byte[0]));

		this.eventSink.tryEmitNext(new InstanceEndpointsDetectedEvent(id, 1L, Endpoints.empty()));
		Thread.sleep(100);

		assertThat(this.cache.get(id, "configprops", null)).isEmpty();
	}

	@Test
	void should_not_invalidate_on_registered_event() throws InterruptedException {
		InstanceId id = InstanceId.of("id4");
		Registration reg = Registration.create("app", "http://localhost/mgmt").build();
		this.cache.put(id, "mappings", null, new CacheEntry(200, new HttpHeaders(), new byte[0]));

		this.eventSink.tryEmitNext(new InstanceRegisteredEvent(id, 1L, reg));
		Thread.sleep(100);

		assertThat(this.cache.get(id, "mappings", null)).isPresent();
	}

}
