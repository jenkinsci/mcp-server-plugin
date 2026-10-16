# Changelog

## Unreleased

* MCP POST requests without an `Authorization` header now require a CSRF crumb. Anonymous and session-cookie clients need a crumb, or an administrator must opt in with `-Dio.jenkins.plugins.mcp.server.Endpoint.allowUnauthenticatedMcp=true` (default `false`). Enabling that property weakens CSRF protection. Requests that send an `Authorization` header stay exempt, because a cross-site page cannot set that header. `mvn hpi:run` sets the property so local development keeps working.
