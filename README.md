# MCP Server Plugin for Jenkins

The MCP (Model Context Protocol) Server Plugin for Jenkins implements the server-side component of the Model Context Protocol. This plugin enables Jenkins to act as an MCP server, providing context, tools, and capabilities to MCP clients, such as LLM-powered applications or IDEs.

## Features

- **MCP Server Implementation**: Implements the server-side of the Model Context Protocol.
- **Jenkins Integration**: Exposes Jenkins functionalities as MCP tools and resources.
- **Real-time Communication**: Uses Server-Sent Events (SSE) for efficient, real-time communication with clients.
- **Extensible Architecture**: Allows easy extension of MCP capabilities through the `McpServerExtension` interface.

## Key Components

1. **Endpoint**: The main entry point for MCP communication, handling SSE connections and message routing.
2. **DefaultMcpServer**: Implements `McpServerExtension`, providing default tools for interacting with Jenkins jobs and builds.
3. **McpToolWrapper**: Wraps Java methods as MCP tools, handling parameter parsing and result formatting.
4. **McpServerExtension**: Interface for extending MCP server capabilities.

## MCP SDK Version

This MCP Server is based on the MCP Java SDK version 0.10.0, which implements the MCP specification version 2024-11-05. We plan to upgrade to the next SDK version when it becomes available, which will likely implement the MCP specification version 2025-03-26.

## Getting Started

### Prerequisites

- Jenkins (version 2.479 or higher)

### Configuration

The MCP Server plugin automatically sets up necessary endpoints and tools upon installation, requiring no additional configuration.

## Usage

### Connecting to the MCP Server

MCP clients can connect to the server using:

- SSE Endpoint: `<jenkins-url>/mcp-server/sse`
- Message Endpoint: `<jenkins-url>/mcp-server/message`

### Authentication and Credentials

The MCP Server Plugin requires the same credentials as the Jenkins instance it's running on. To authenticate your MCP queries:

1. **Jenkins API Token**: Generate an API token from your Jenkins user account.
2. **Basic Authentication**: Use the API token in the HTTP Basic Authentication header. Below is an example of VS code settings.xml
```json
{
  "mcp": {
    "servers": {
      "jenkins": {
        "type": "sse",
        "url": "https://jenkins-host/mcp-server/sse",
        "headers": {
          "Authorization": "Basic <user:token base64>"
        }
      }
    }
  }
}
```
Example of using the token:

### Available Tools

The plugin provides built-in tools through the `DefaultMcpServer` class:

- `getBuild`: Retrieve a specific build or the last build of a Jenkins job.
- `getJob`: Get a Jenkins job by its full path.
- `getAllJobs`: Get a list of all Jenkins jobs.
- `triggerBuild`: Trigger a build of a job.
- `getBuildLog`: Retrieves some log lines with pagination for a specific build or the last build of a Jenkins job.

### Extending MCP Capabilities

To add new MCP tools or functionalities:

1. Create a class implementing `McpServerExtension`.
2. Use `@Tool` to expose methods as MCP tools.
3. Use `@ToolParam` to define and describe tool parameters.

Example:

```java
@Extension
public class MyCustomMcpExtension implements McpServerExtension {
	@Tool(description = "My custom tool")
	public String myCustomTool(@ToolParam(description = "Input parameter") String input) {
		// Tool implementation
	}
}
```
### Result Handling

The MCP Server Plugin handles various result types with the following approach:

- **List Results**: Each element in the list is converted to a separate text content item in the response.
- **Single Objects**: The entire object is converted into a single text content item.

For serialization to text content:

- **@ExportedBean Annotation**: If the result object is annotated with `@ExportedBean` (from `org.kohsuke.stapler.export`), Jenkins' `org.kohsuke.stapler.export.Flavor.JSON` exporting mechanism is used.
- **Other Objects**: For objects without the `@ExportedBean` annotation, Jackson is used for JSON serialization.

This approach ensures flexible and efficient handling of different result types, accommodating both Jenkins-specific exported objects and standard Java objects.
This flexible approach ensures that tool results are consistently and accurately represented in the MCP response, regardless of their complexity.
### Integration with GitHub Copilot
The MCP Server Plugin seamlessly integrates with GitHub Copilot, enhancing your development experience by providing direct access to Jenkins information within your IDE. This integration allows you to interact with Jenkins jobs and builds using natural language queries.
![GitHub Copilot Integration](doc/copilot.png)

As shown in the screenshot:
1. You can ask Copilot about Jenkins jobs using natural language, e.g., "list jenkins job under root".
2. Copilot uses the MCP Server to fetch and display information about Jenkins jobs, listing the jobs under the root directory.
3. You can also request specific information, such as "get the last build status of job a", and Copilot will provide the relevant details including build number, status, and URL.

This integration streamlines your workflow by allowing you to access Jenkins information without leaving your development environment.
### Further Information
For more details on the Model Context Protocol and its Java SDK:
- [MCP Introduction](https://modelcontextprotocol.io/introduction)
- [MCP Java SDK Server Component](https://modelcontextprotocol.io/sdk/java/mcp-server)

### Contributing
Contributions to the MCP Server plugin are welcome. Please refer to the [Jenkins contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) for more information.
### License
This project is licensed under the MIT License - see the [LICENSE](LICENSE.md) file for details.
