# ChatAgent API Documentation

## Overview

ChatAgent is an AI-powered chat application with tool calling capabilities. This document describes all available API endpoints.

## Authentication

All API endpoints (except `/api/auth/login`) require JWT authentication in the `Authorization` header:

```
Authorization: Bearer <your-jwt-token>
```

## Base URL

```
http://localhost:8080/api
```

## Endpoints

### Authentication

#### Login

Authenticate user and receive JWT token.

**Endpoint:** `POST /api/auth/login`

**Request Body:**
```json
{
  "username": "admin",
  "password": "admin"
}
```

**Response (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "username": "admin"
}
```

**Error Response (401 Unauthorized):**
```json
{
  "message": "Invalid credentials"
}
```

#### Logout

Invalidate current session.

**Endpoint:** `POST /api/auth/logout`

**Response (200 OK):**
```json
{
  "message": "Logged out successfully"
}
```

### Sessions

#### Create Session

Create a new chat session.

**Endpoint:** `POST /api/sessions`

**Request Body (Optional):**
```json
{
  "title": "My New Chat"
}
```

**Response (200 OK):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "My New Chat",
  "createdAt": "2024-01-01T00:00:00Z",
  "updatedAt": "2024-01-01T00:00:00Z"
}
```

#### List Sessions

Get all sessions for the authenticated user.

**Endpoint:** `GET /api/sessions`

**Response (200 OK):**
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "title": "My New Chat",
    "createdAt": "2024-01-01T00:00:00Z",
    "updatedAt": "2024-01-01T00:00:00Z"
  }
]
```

#### Update Session Title

Update the title of an existing session.

**Endpoint:** `PATCH /api/sessions/{sessionId}`

**Request Body:**
```json
{
  "title": "Updated Title"
}
```

**Response (200 OK):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Updated Title",
  "createdAt": "2024-01-01T00:00:00Z",
  "updatedAt": "2024-01-01T00:01:00Z"
}
```

**Error Response (404 Not Found):**
```json
{
  "message": "Session not found"
}
```

**Error Response (403 Forbidden):**
```json
{
  "message": "Forbidden"
}
```

#### Delete Session

Delete a session and all its messages.

**Endpoint:** `DELETE /api/sessions/{sessionId}`

**Response (204 No Content):**

**Error Response (404 Not Found):**
```json
{
  "message": "Session not found"
}
```

**Error Response (403 Forbidden):**
```json
{
  "message": "Forbidden"
}
```

#### Get Session Messages

Get all messages for a specific session.

**Endpoint:** `GET /api/sessions/{sessionId}/messages`

**Response (200 OK):**
```json
[
  {
    "id": 1,
    "role": "USER",
    "content": "Hello, how are you?",
    "toolCallsJson": null,
    "toolCallId": null,
    "createdAt": "2024-01-01T00:00:00Z"
  },
  {
    "id": 2,
    "role": "ASSISTANT",
    "content": "I'm doing well, thank you!",
    "toolCallsJson": null,
    "toolCallId": null,
    "createdAt": "2024-01-01T00:00:01Z"
  }
]
```

**Error Response (404 Not Found):**
```json
{
  "message": "Session not found"
}
```

**Error Response (403 Forbidden):**
```json
{
  "message": "Forbidden"
}
```

### Agent Chat

#### Chat (Synchronous)

Send a message to the agent and receive a response synchronously.

**Endpoint:** `POST /api/agent/chat/sync`

**Request Body:**
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "content": "Calculate 123 * 456"
}
```

**Response (200 OK):**
```json
{
  "reply": "The result of 123 * 456 is 56088.",
  "steps": [
    {
      "type": "plan",
      "stepIndex": 1,
      "detail": "Use calculator tool to compute 123 * 456"
    },
    {
      "type": "tool",
      "toolName": "calculator",
      "detail": "56088.0"
    }
  ]
}
```

**Error Response (400 Bad Request):**
```json
{
  "message": "Invalid request parameters"
}
```

**Error Response (429 Too Many Requests):**
```json
{
  "message": "Rate limit exceeded"
}
```

#### Chat (Streaming)

Send a message to the agent and receive a streaming response via Server-Sent Events (SSE).

**Endpoint:** `POST /api/agent/chat/stream`

**Request Headers:**
```
Content-Type: application/json
Accept: text/event-stream
Authorization: Bearer <your-jwt-token>
```

**Request Body:**
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "content": "What's the weather in Shanghai?"
}
```

**Response (200 OK):**
```
event: delta
data: {"text":"The"}

event: delta
data: {"text":" weather"}

event: plan_start
data: {"count":1}

event: plan_step
data: {"stepIndex":1,"text":"Check weather for Shanghai"}

event: tool_start
data: {"name":"get_mock_weather","id":""}

event: tool_end
data: {"name":"get_mock_weather","ok":true,"detail":"Sunny, 25°C"}

event: done
data: {"ok":true}
```

**SSE Event Types:**

- `delta`: Text chunk of the assistant's response
- `plan_start`: Agent started planning (contains step count)
- `plan_step`: Individual planning step
- `plan_done`: Planning completed
- `tool_start`: Tool execution started
- `tool_end`: Tool execution completed
- `guardrail`: Security guardrail triggered
- `error`: Error occurred
- `done`: Response completed

**Error Response (400 Bad Request):**
```json
{
  "message": "Invalid request parameters"
}
```

**Error Response (429 Too Many Requests):**
```json
{
  "message": "Rate limit exceeded"
}
```

### Health Check

#### Health Status

Check the health status of the application.

**Endpoint:** `GET /api/health`

**Response (200 OK):**
```json
{
  "status": "UP"
}
```

## Data Models

### SessionResponse

```typescript
{
  id: string;           // UUID
  title: string;        // Session title
  createdAt: string;    // ISO 8601 timestamp
  updatedAt: string;    // ISO 8601 timestamp
}
```

### MessageResponse

```typescript
{
  id: number;               // Message ID
  role: 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM';
  content: string | null;    // Message content
  toolCallsJson: string | null;   // Tool calls JSON (for ASSISTANT)
  toolCallId: string | null;      // Tool call ID (for TOOL)
  createdAt: string;        // ISO 8601 timestamp
}
```

### AgentStepResponse

```typescript
{
  type: 'plan' | 'tool';
  stepIndex?: number;      // For plan steps
  toolName?: string;       // For tool calls
  detail: string;          // Step/tool detail
}
```

### AgentChatResponse

```typescript
{
  reply: string;           // Assistant's final response
  steps: AgentStepResponse[];  // Execution steps
}
```

## Error Handling

All endpoints may return the following error responses:

### 400 Bad Request
```json
{
  "message": "Invalid request parameters"
}
```

### 401 Unauthorized
```json
{
  "message": "Unauthorized"
}
```

### 403 Forbidden
```json
{
  "message": "Forbidden"
}
```

### 404 Not Found
```json
{
  "message": "Resource not found"
}
```

### 429 Too Many Requests
```json
{
  "message": "Rate limit exceeded"
}
```

### 500 Internal Server Error
```json
{
  "message": "Internal server error"
}
```

## Rate Limiting

The API implements rate limiting to prevent abuse:

- Default limit: 10 requests per minute per user
- Rate limit headers are included in responses:
  - `X-RateLimit-Limit`: Maximum requests per window
  - `X-RateLimit-Remaining`: Remaining requests in current window
  - `X-RateLimit-Reset`: Unix timestamp when the window resets

## Tools

The agent can use the following tools:

### Calculator
- **Name:** `calculator`
- **Description:** Evaluate numeric arithmetic expressions
- **Parameters:** 
  - `expression` (string): Arithmetic expression (e.g., "123*456")

### Mock Weather
- **Name:** `get_mock_weather`
- **Description:** Get mock weather information for a city
- **Parameters:**
  - `city` (string): City name

### Knowledge Search
- **Name:** `search_knowledge`
- **Description:** Search local markdown knowledge base
- **Parameters:**
  - `query` (string): Search query
  - `k` (integer, optional): Number of results (default: 3)
  - `minScore` (number, optional): Minimum similarity score (default: 0.0)
  - `docTitleFilter` (string, optional): Filter by document title

## Examples

### Example 1: Simple Chat

```bash
# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'

# Create session
curl -X POST http://localhost:8080/api/sessions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{"title":"Example Chat"}'

# Send message
curl -X POST http://localhost:8080/api/agent/chat/sync \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{"sessionId":"SESSION_ID","content":"Hello!"}'
```

### Example 2: Tool Usage

```bash
# Send message that requires calculator tool
curl -X POST http://localhost:8080/api/agent/chat/sync \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{"sessionId":"SESSION_ID","content":"What is 123 * 456?"}'
```

### Example 3: Streaming Response

```bash
# Send message with streaming response
curl -X POST http://localhost:8080/api/agent/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{"sessionId":"SESSION_ID","content":"Tell me a story"}'
```

## WebSocket Support

The application uses Server-Sent Events (SSE) for real-time streaming. For full-duplex communication, consider implementing WebSocket support in future versions.

## Versioning

Current API version: v1

All endpoints are prefixed with `/api`. Future versions may introduce versioning (e.g., `/api/v2/...`).

## Support

For issues or questions, please refer to the project documentation or contact the development team.