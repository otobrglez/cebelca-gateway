# Cebelca.biz API

Slovenian invoicing service. The public REST API and the web UI share the **same
engine and `_r`/`_m` protocol** — they differ only in authentication.

## Auth

- **Public API** — `https://www.cebelca.biz/API`, HTTP Basic Auth: API key as
  username, literal `x` as password (`-u KEY:x`).
- **UI backend** — `https://www.cebelca.biz/manage/DR-srv.html`, session cookie
  `RSPSID` (login: GET `sign-in.html`, then POST `email`/`password`/`sign-in=Vstopite`).

## Request shape

Always `POST`. Resource + method go in the query string, args in the
`x-www-form-urlencoded` body.

```
POST /API?_r=<resource>&_m=<method>[&_f=json|csv|tsv][&_x=1]
Authorization: Basic base64(KEY:x)
Content-Type: application/x-www-form-urlencoded

field1=val1&field2=val2
```

- `_f` — output format (default `json`).
- `_x=1` — explore mode: no `_r` → resource list; `_r` only → methods; `_r`+`_m` → args.
- `_m2`,`_m3` — stack methods in one call (result of the last is returned).
- Only POST works (GET → 401). PDFs use a separate endpoint (see below).

## Response envelope

Data is **doubly nested**: `result[0]` = rows, `result[0][0]` = first row.

```jsonc
// success (select)
[[ {"id":8,"name":"Demo Company", ...}, {...} ]]

// insert → new id
[[ {"id": 3} ]]

// validation error (HTTP 403)
["validation", {"date_sent":"required","date_to_pay":"required","id_partner":"required"}]

// row-level error
[[ {"err": "..."} ]]
```

## Common methods (most resources)

| Method | Purpose |
|---|---|
| `select-all` | all rows |
| `select-one` (`id`) | one row |
| `insert-into` (fields) | insert, returns `[[{"id":N}]]` |
| `insert-select` | insert, returns the stored row |
| `update-select` (`id`+fields) | update |
| `delete` (`id`) | delete |
| `assure` (fields) | find-or-create exact match, returns id (idempotent) |
| `export-all` | bulk export |

Dates are **`DD.MM.YYYY`** on input (e.g. `22.12.2015`); reads return ISO `YYYY-MM-DD`.

## Key resources

| Resource | Description |
|---|---|
| `partner` | Contacts / customers |
| `invoice-sent` | Sent invoice heads |
| `invoice-sent-b` | Sent invoice line items (bodies) |
| `invoice-sent-p` | Sent invoice payments |
| `invoice-sent-o` | Items/services on invoice bodies |
| `item` | Services / goods (pricelist) |
| `preinvoice` / `preinvoice-b` | Proforma / estimate head + lines |
| `stat` | Statistics |
| `transfer` / `transfer-b` | Warehouse / inventory docs |

Full list (~50) via `POST /API?_x=1`.

## Examples (real output)

### Partner
```bash
curl -k -u $KEY:x -d "" "https://www.cebelca.biz/API?_r=partner&_m=select-all"
```
```json
[[{"id":8,"name":"Demo Company","street":"","postal":"","city":"","vatid":"",
  "vatbound":1,"payment_period":14,"country":"","lang":"si","disabled":0,
  "id_pricelist":0,"name_lc":"demo company"}]]
```

Idempotent create (returns id):
```bash
curl -k -u $KEY:x \
  -d "name=My Company&street=Downing street&postal=E1w201&city=London" \
  "https://www.cebelca.biz/API?_r=partner&_m=assure"
# [[{"id":9}]]
```

### Invoice head
```bash
curl -k -u $KEY:x -d "" "https://www.cebelca.biz/API?_r=invoice-sent&_m=select-all"
```
```json
[[{"id":2,"title":"26-0002","date_sent":"2026-07-20","date_to_pay":"2026-07-20",
  "date_served":"2026-07-20","id_partner":7,"vat_level":0.0,"payment":"paid",
  "id_currency":0,"fiscal":null,"fiscalized":null,"version":1}]]
```

Create:
```bash
curl -k -u $KEY:x \
  -d "date_sent=22.12.2015&date_to_pay=30.12.2015&date_served=22.12.2015&id_partner=7" \
  "https://www.cebelca.biz/API?_r=invoice-sent&_m=insert-into"
# [[{"id":3}]]
```

### Invoice line item
```bash
curl -k -u $KEY:x \
  -d "title=Programiranje&qty=10&mu=kos&price=120&vat=22&discount=0&id_invoice_sent=3" \
  "https://www.cebelca.biz/API?_r=invoice-sent-b&_m=insert-into"
```
```json
[[{"id":1,"id_invoice_sent":1,"title":"Programiranje","qty":1.0,"mu":"kos",
  "price":300.0,"vat":22.0,"discount":0.0,"tax_type":"DDV","sortorder":1}]]
```

### Service / item
```bash
curl -k -u $KEY:x -d "" "https://www.cebelca.biz/API?_r=item&_m=select-all"
```
```json
[[{"id":1,"code":"A101","descr":"Primer artikla","price":220.0,"unit":"kos",
  "tax":22.0,"sales_item":1,"disabled":0}]]
```

### PDF (separate endpoint)
```bash
curl -k -u $KEY:x -J -O \
  "https://www.cebelca.biz/API-pdf?id=1&format=PDF&res=invoice-sent&lang=si&disposition=inline&preview=0"
# binary PDF
```

## Invoice flow

1. `partner assure` → partner id
2. `invoice-sent insert-smart-2` (or `insert-into`) → invoice id
3. `invoice-sent-b insert-into` per line (`id_invoice_sent=<id>`)
4. optional `invoice-sent-p insert-into` / `mark-paid` → payment
5. **finalize** (invoices start as drafts / OSNUTEK):
   - `invoice-sent finalize-invoice` — with FURS fiscal registration
   - `invoice-sent finalize-invoice-2015` — without fiscal data
   - `invoice-sent get-fiscal-info` (`id`) — ZOI/EOR info
6. `API-pdf` → PDF

## UI-only (not on public API)

Reachable only via the session-authenticated `/manage/` backend:

- Resources: `my-company` (company profile), `template` (invoice templates),
  `konto-group` (ledger account groups) — via `DR-srv-pl.html` (`_m=change`).
- Servlets: `ZIP-srv.html` (bulk export), `DOC-srv.html` (doc generation),
  `call_stripe_paymentlink.html` (Stripe links), `import-data.html` (import),
  `*.rjs` (POS, FIFO history, chart fragments).

## Notes

- Server occasionally returns single-quoted pseudo-JSON — retry after `'`→`"`.
- Retry with exponential backoff on connection failure, HTTP ≥400, and JSON parse errors.
- Official docs: <https://github.com/InvoiceFox/Workonomic-API-bash/blob/master/API-docs.md>
