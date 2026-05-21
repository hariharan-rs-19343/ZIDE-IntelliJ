# CMTool API — Full Response Reference

## Product Response Schema

Complete fields returned by `GET /api/v1/products/{id}` or `GET /api/v1/products?name=...`:

```json
{
  "products": {
    "id": 7243,
    "name": "PRODUCT_NAME",
    "known_as": "PRODUCT_DISPLAY_NAME",
    "group_id": 16,
    "repository_type_id": 5,
    "zrepo_id": "26000119703269",
    "download_url": "https://build.zohocorp.com/group/product",
    "us_download_url": null,
    "module_name": "product_module",
    "build_owner_id": null,
    "is_active": true,
    "is_released": false,
    "is_webhost_enabled": true,
    "is_build_lock_configuration_enabled": 0,
    "average_build_time": null,
    "min_build_time": null,
    "max_build_time": null,
    "is_ci_enabled": false,
    "is_createlink_needed": false,
    "ci_type": null,
    "info": null,
    "team_email_id": "team@zohocorp.com",
    "srclabel": "HEAD",
    "repository_url": "https://repository.zohocorpcloud.in/zohocorp/group/product.git",
    "repository_path": null,
    "thirdparty_label": null,
    "created_at": "2024-10-23T16:15:49.000+05:30",
    "updated_at": "2025-09-27T12:20:14.000+05:30",
    "service_name": "product_service",
    "is_parallel_enabled": true,
    "product_category_id": null,
    "is_public": true,
    "build_lock_limit_exclude": false,
    "sync_status": false,
    "last_synced": null,
    "is_maven_configuration_enabled": false,
    "is_new_server_allocation_enabled": false,
    "is_nic_deployment_enabled": false,
    "sync_tags": false,
    "migration_repository_url": null
  }
}
```

### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `id` | int | Unique product identifier |
| `name` | String | Product name (uppercase convention) |
| `known_as` | String | Display alias |
| `group_id` | int | Product group/team identifier |
| `repository_type_id` | int | Repository backend type (e.g. 5=git, 6=svn) |
| `zrepo_id` | String | Zoho Repository internal ID |
| `download_url` | String | Build download URL |
| `us_download_url` | String? | US data center download URL |
| `module_name` | String | Module identifier |
| `build_owner_id` | int? | Build owner user ID |
| `is_active` | boolean | Whether product is active |
| `is_released` | boolean | Whether product is publicly released |
| `is_webhost_enabled` | boolean | WebHost deployment enabled |
| `is_build_lock_configuration_enabled` | int | Build lock config flag |
| `average_build_time` | int? | Average build duration (seconds) |
| `min_build_time` | int? | Minimum observed build time |
| `max_build_time` | int? | Maximum observed build time |
| `is_ci_enabled` | boolean | CI integration enabled |
| `is_createlink_needed` | boolean | Whether createlink step is needed |
| `ci_type` | String? | CI system type (e.g. "jenkins") |
| `info` | String? | Free-text product info |
| `team_email_id` | String | Team contact email |
| `srclabel` | String | Source label (e.g. "HEAD") |
| `repository_url` | String | Source repository URL |
| `repository_path` | String? | Repository sub-path |
| `thirdparty_label` | String? | Third-party dependency label |
| `created_at` | String | ISO-8601 creation timestamp |
| `updated_at` | String | ISO-8601 last-update timestamp |
| `service_name` | String | Service identifier |
| `is_parallel_enabled` | boolean | Parallel build enabled |
| `product_category_id` | int? | Category classifier |
| `is_public` | boolean | Public visibility flag |
| `build_lock_limit_exclude` | boolean | Excluded from build lock limits |
| `sync_status` | boolean | Repository sync status |
| `last_synced` | String? | Last sync timestamp |
| `is_maven_configuration_enabled` | boolean | Maven integration flag |
| `is_new_server_allocation_enabled` | boolean | New server allocation flag |
| `is_nic_deployment_enabled` | boolean | NIC deployment flag |
| `sync_tags` | boolean | Tag sync enabled |
| `migration_repository_url` | String? | Migration target repository URL |

## User Response Schema

```json
{
  "users": [
    {
      "id": 42,
      "username": "john",
      "email": "john@zohocorp.com",
      "is_active": true,
      "is_admin": true,
      "created_at": null,
      "updated_at": "2019-06-27T23:34:19.000+05:30",
      "last_visited_product_id": 38
    }
  ],
  "meta": {
    "total_pages": 1,
    "per_page": 20,
    "total_count": 1
  }
}
```

### User Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `id` | int | Unique user identifier |
| `username` | String | Login username |
| `email` | String | User email address |
| `is_active` | boolean | Whether account is active |
| `is_admin` | boolean | `true` only for CMTool administrators |
| `created_at` | String? | Account creation timestamp |
| `updated_at` | String | Last update timestamp |
| `last_visited_product_id` | int? | Last visited product ID |

## Pagination Meta

All list endpoints return pagination metadata:

| Field | Type | Description |
|-------|------|-------------|
| `total_pages` | int | Total number of pages |
| `per_page` | int | Results per page (default 20) |
| `total_count` | int | Total matching records |

Navigate pages with `?page=N` query parameter.

## HTTP Headers

| Header | Type | Required | Description |
|--------|------|----------|-------------|
| `PRIVATE-TOKEN` | String | Yes | Authentication token |
| `Content-Type` | String | No | Request content type (use `application/json` for POST/PUT) |
