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

## Accounting

- `accounting.ledgeraccounts.List` reads the chart of accounts.
- `accounting.ledgerentrylines.List` reads general-ledger lines. Filters include `date` and `ledger_account_id`.
- `accounting.ledgerentries.List` reads `GET /ledger_entries`. API v2 has no `journal_entries` resource. Pagination uses `page` and `per_page` (`total_pages` tells the plugin when to stop). Filter by `journalId` and `dateFrom` / `dateTo`. Sort defaults to `-date`.
- `accounting.trialbalance.Get` calls `GET /trial_balance`. `periodStart` and `periodEnd` are required (`period_start`, `period_end`). The body is a cursor page of `number`, `formatted_number`, `label`, `debits`, and `credits` (`limit` 1–1000). The task follows `next_cursor`. The token needs the `trial_balance:readonly` scope.

## Changelogs

`changelogs.List` reads `GET /changelogs/{resource}` for `supplier_invoices`, `customer_invoices`, `transactions`, `ledger_entry_lines`, `customers`, or `suppliers`.

Each item is:

- `id`: the resource id
- `operation`: `insert`, `update`, or `delete`
- `processed_at`, `updated_at`, `created_at`

Events are oldest-first and kept for about four weeks. `since` is sent as `start_date` on the first request only. The next page sends `cursor` and `limit` and drops `start_date` (sending both returns HTTP 400). A `start_date` older than the retention window returns HTTP 422. `pageSize` may be up to 1000 and defaults to 100.

## Triggers and the namespace KV watermark

- `supplierinvoices.SupplierInvoiceTrigger` emits supplier invoices created or updated since the watermark.
- `customerinvoices.CustomerInvoicePaidTrigger` emits customer invoices whose `paid` field is true. `paid` is not a list filter.
- `transactions.TransactionTrigger` reads `changelogs/transactions`, then `GET /transactions` with `id` `in` batches of 100. Optional `bankAccountId` is `bank_account_id` `eq`. `categorized` is a post-filter, not a query parameter. The live transaction object exposes `bank_account` and `categories`, not a `categorized` flag; the boolean is honored when a payload includes it.

The watermark key is `pl_wm_<flowIdLength>_<flowId>_<triggerIdLength>_<triggerId>` in the flow namespace. Its value is the last `processed_at` fully consumed, then the resource ids that share that timestamp. The checkpoint moves only after `has_more` is false, and it moves on empty polls too. A poll that only sees deletes, or whose invoice fetch fails, does not start an execution.

## Supplier invoices

`paymentStatus` accepts `to_be_processed`, `to_be_paid`, `partially_paid`, `payment_error`, `payment_scheduled`, `payment_in_progress`, `payment_emitted`, `payment_found`, `paid_offline`, and `fully_paid`. `categoryIds` is sent as `category_id` with operator `in`.

`supplierinvoices.Download` loads the invoice, then downloads `public_file_url` (about 30 minutes) or `source_file_url` without the `Authorization` header.

## Master data search

`masterdata.customers.List` and `masterdata.suppliers.List` accept `search`, sent as `name` `start_with`.

## Rate limit

HTTP 429 is retried up to 5 times. A numeric `Retry-After` header is honored; otherwise the plugin backs off from 1s, doubling up to 30s.
