---
sidebar_position: 25
sidebar_custom_props:
  icon: 'key'
---

# OAuth2 Client Credentials

Use the OAuth2 Client Credentials flow for machine-to-machine authentication between Spring Boot Admin components.

## Overview

The OAuth2 Client Credentials flow is the standard pattern for machine-to-machine (M2M) authentication where no
user session is involved. Spring Boot Admin supports it in two communication paths:

1. **Client → Server**: when a client application registers itself on the SBA server
2. **Server → Instances**: when the SBA server polls actuator endpoints on registered instances

This support is **opt-in** and requires `spring-security-oauth2-client` on the classpath. Existing HTTP Basic Auth
behaviour is unchanged.

```mermaid
graph TD
    A["Client Application<br/>Obtains Bearer token<br/>via Client Credentials"] -->|Registration POST<br/>Authorization: Bearer ...| B["Spring Boot Admin Server"]
    B -->|Actuator polling<br/>Authorization: Bearer ...| C["Instance Actuator<br/>Secured with OAuth2"]
```

---

## Client Side — Registering with OAuth2

Use this when the SBA server requires a Bearer token for its `/instances` registration endpoint.

### Dependencies

Add `spring-boot-starter-oauth2-client` to your client application:

**Maven**:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

**Gradle**:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
```

### Configuration

```yaml title="application.yml"
spring:
  security:
    oauth2:
      client:
        registration:
          sba-client:
            authorization-grant-type: client_credentials
            client-id: ${OAUTH2_CLIENT_ID}
            client-secret: ${OAUTH2_CLIENT_SECRET}
        provider:
          sba-client:
            token-uri: https://your-authorization-server/oauth2/token

  boot:
    admin:
      client:
        url: http://admin-server:8080
        # Reference the registration ID above
        oauth2-client-registration-id: sba-client
```

:::note
When `oauth2-client-registration-id` is set, Basic Auth (`username` / `password`) is ignored for the registration
request. The two mechanisms are mutually exclusive.
:::

### How It Works

When `spring-security-oauth2-client` is on the classpath and an `OAuth2AuthorizedClientManager` bean is present in
the context, Spring Boot Admin auto-configures an `OAuth2ClientHttpRequestInterceptor` on the registration
`RestClient`. The interceptor obtains (and caches/refreshes) a Bearer token from your Authorization Server using
the configured `client_credentials` grant, then attaches it as an `Authorization: Bearer <token>` header on every
registration request.

---

## Server Side — Polling Instances with OAuth2

Use this when registered instances expose actuator endpoints that are secured with OAuth2 Bearer token
authentication.

### Dependencies

Add `spring-boot-starter-oauth2-client` to your SBA server:

**Maven**:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

**Gradle**:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
```

### Configuration

```yaml title="application.yml"
spring:
  security:
    oauth2:
      client:
        registration:
          instances-client:
            authorization-grant-type: client_credentials
            client-id: ${OAUTH2_CLIENT_ID}
            client-secret: ${OAUTH2_CLIENT_SECRET}
        provider:
          instances-client:
            token-uri: https://your-authorization-server/oauth2/token

  boot:
    admin:
      instance-auth:
        oauth2:
          # Default registration ID for all instances
          default-registration-id: instances-client
          # Per-service override (key = spring.application.name of the registered service)
          service-map:
            payment-service: payment-service-client
            inventory-service: inventory-service-client
```

:::note
When a `ReactiveOAuth2AuthorizedClientManager` bean is present, the OAuth2 headers provider is registered alongside
the existing `BasicAuthHttpHeaderProvider`. If both are active, both sets of headers are merged — configure only one
mechanism per environment to avoid conflicts.
:::

### Per-Service Registration IDs

The `service-map` key is the service name as registered in Spring Boot Admin (usually `spring.application.name`).
Values in `service-map` take precedence over `default-registration-id`.

If neither a service-specific override nor a default registration ID is configured for an instance, no OAuth2 header
is added for that instance.

---

## Combined Example

Below is a minimal end-to-end example where both client registration and instance polling use OAuth2:

### Client (`payment-service`)

```yaml title="application.yml"
spring:
  application:
    name: payment-service

  security:
    oauth2:
      client:
        registration:
          sba-registration:
            authorization-grant-type: client_credentials
            client-id: payment-service
            client-secret: ${CLIENT_SECRET}
        provider:
          sba-registration:
            token-uri: https://auth.company.com/oauth2/token

  boot:
    admin:
      client:
        url: https://admin.company.com
        oauth2-client-registration-id: sba-registration
```

### Server (`spring-boot-admin`)

```yaml title="application.yml"
spring:
  security:
    oauth2:
      client:
        registration:
          actuator-client:
            authorization-grant-type: client_credentials
            client-id: sba-server
            client-secret: ${CLIENT_SECRET}
        provider:
          actuator-client:
            token-uri: https://auth.company.com/oauth2/token

  boot:
    admin:
      instance-auth:
        oauth2:
          default-registration-id: actuator-client
```

---

## See Also

- [Actuator Security](./20-actuator-security.md) — HTTP Basic Auth for actuator endpoints
- [Server Authentication](./10-server-authentication.md) — Secure the Admin Server UI and API
