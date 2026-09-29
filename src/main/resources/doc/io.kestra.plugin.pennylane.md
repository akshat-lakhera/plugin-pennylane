Pennylane is a leading European cloud accounting, financial management, and invoicing SaaS platform.
This plugin enables end-to-end automation with Pennylane by orchestrating supplier invoices, customer invoices, bank transactions, and master data entities.

## Authentication

Authentication against the Pennylane API is handled via an API token generated in your company or firm settings:
- Provide your token via the `apiToken` property (recommended: use `{{ secret('PENNYLANE_API_TOKEN') }}`).
- The base URL defaults to `https://app.pennylane.com/api/external/v2` and can be overridden via `baseUrl`.

## Core Features

- **Cursor-based Pagination**: Automatically pages through multi-page result sets until all records are retrieved, with optional caps via `maxRecords`.
- **Flexible Ingestion Modes (`fetchType`)**:
  - `FETCH`: In-memory list available as `{{ outputs.taskId.rows }}`.
  - `FETCH_ONE`: Single item available as `{{ outputs.taskId.row }}`.
  - `STORE`: Streams items directly into Kestra internal storage (`.ion` format) for large-volume batch workflows.
  - `NONE`: Counts records without retaining them in memory.
- **Resilient Rate Limiting**: Built-in exponential backoff and `Retry-After` header parsing gracefully handles HTTP 429 rate limits.
- **Rich Querying & Filtering**: Supports Pennylane's filter DSL as well as typed properties (`dateFrom`, `dateTo`, `supplierId`, `customerId`, `paymentStatus`, etc.).
