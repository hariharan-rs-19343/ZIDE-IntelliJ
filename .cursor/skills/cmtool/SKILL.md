---
name: cmtool
description: Interact with the Zoho CMTool REST API for users and products — querying users by username/email, looking up products by id/name/repository_url/download_url, and creating/updating/deleting resources. Use when the user mentions CMTool, CMTools, PRIVATE-TOKEN, cmtools.csez, product lookup, build URL, repository URL, or needs to fetch product/user metadata from the Zoho build infrastructure.
---

# CMTool API

Zoho internal REST API at `https://cmtools.csez.zohocorpin.com/api/v1/`. Only `zohocorp.com` users may call it. Responses are JSON.

## Authentication

Every request requires the header:

```
PRIVATE-TOKEN: <auth_token>
```

In the ZIDE IntelliJ plugin the token is stored via `ZideSettingsState.cmToolAuthToken` (Settings > Tools > Zide > CMTool). Always ensure the token is set before making API calls.

## Endpoints

### Users — `/api/v1/users`

Retrieve application users (admin-only for creation).

| Method | Description |
|--------|-------------|
| GET    | Retrieve users |
| POST   | Create user |
| PUT    | Update user |
| DELETE | Delete user |

**Query parameters** (at least one required):

| Parameter | Type   |
|-----------|--------|
| username  | String |
| email     | String |

**Example request:**

```
GET https://cmtools.csez.zohocorpin.com/api/v1/users?username=john
```

**Example response:**

```json
{
  "users": [
    {
      "id": 42,
      "username": "john",
      "email": "john@zohocorp.com",
      "is_active": true,
      "is_admin": false,
      "created_at": null,
      "updated_at": "2019-06-27T23:34:19.000+05:30",
      "last_visited_product_id": 38
    }
  ],
  "meta": { "total_pages": 1, "per_page": 20, "total_count": 1 }
}
```

### Products — `/api/v1/products`

Retrieve product/service metadata (build URLs, repository URLs, CI config, etc.).

| Method | Description |
|--------|-------------|
| GET    | Retrieve products |
| POST   | Create product |
| PUT    | Update product |
| DELETE | Delete product |

**Query parameters** (at least one required for search):

| Parameter      | Type   | Mandatory |
|----------------|--------|-----------|
| id             | String | required (for single lookup) |
| name           | String | optional  |
| download_url   | String | optional  |
| repository_url | String | optional  |
| team_mail_id   | String | optional  |

**Single product by ID:**

```
GET https://cmtools.csez.zohocorpin.com/api/v1/products/PRODUCT_ID
```

**Search by name/repository:**

```
GET https://cmtools.csez.zohocorpin.com/api/v1/products?name=XXX&repository_url=http://build/xxx/xxxxx
```

**Example response:**

For detailed product response fields, see [reference.md](reference.md).

## Key Product Fields

| Field | Description |
|-------|-------------|
| id | Unique product identifier |
| name | Product name (uppercase convention) |
| known_as | Display name / alias |
| download_url | Build download base URL |
| repository_url | Source repository URL |
| module_name | Module identifier |
| service_name | Service identifier |
| team_email_id | Team contact email |
| is_active | Whether product is active |
| is_released | Whether product has been released |
| is_webhost_enabled | WebHost deployment flag |
| is_ci_enabled | CI integration flag |
| ci_type | CI system type (e.g. "jenkins") |
| srclabel | Source label (e.g. "HEAD") |

## Usage in ZIDE Plugin

The CMTool API is used during **New Project** creation:

1. `ZideNewProjectAction` and `ZideModuleBuilder` call `ensureCmToolToken()` before proceeding
2. The token is stored in `ZideSettingsState.cmToolAuthToken` (persisted in `dzide-settings.xml`)
3. The wizard dialog (`ZideProjectWizardDialog`) uses product data to populate the **Service** dropdown
4. Product fields like `repository_url`, `download_url`, and `service_name` drive git clone and build download

### Making HTTP Calls in Kotlin (IntelliJ Plugin)

When calling CMTool from the plugin, use `java.net.HttpURLConnection` or OkHttp:

```kotlin
val url = URL("https://cmtools.csez.zohocorpin.com/api/v1/products?name=$productName")
val conn = url.openConnection() as HttpURLConnection
conn.requestMethod = "GET"
conn.setRequestProperty("PRIVATE-TOKEN", settings.cmToolAuthToken)
conn.setRequestProperty("Content-Type", "application/json")

val response = conn.inputStream.bufferedReader().readText()
val json = JsonParser.parseString(response).asJsonObject
```

## Pagination

List responses include a `meta` object:

```json
{
  "meta": {
    "total_pages": 5,
    "per_page": 20,
    "total_count": 97
  }
}
```

Use `page` query parameter to paginate: `?name=XXX&page=2`.

## Error Handling

- **401 Unauthorized** — invalid or missing `PRIVATE-TOKEN`
- **404 Not Found** — product/user ID does not exist
- **422 Unprocessable Entity** — missing required parameters

Always validate `PRIVATE-TOKEN` is non-empty before making requests. In the plugin, prompt the user via `Messages.showInputDialog` if the token is missing.

## Additional Resources

- For complete product response schema, see [reference.md](reference.md)
- CMTool Auth Token setup guide: [Zoho Learn FAQ](https://learn.zoho.in/portal/zohocorp/manual/zide-faqs/article/cmtools-auth-token-required)
