# API Examples

Base URL: `http://localhost:8080`

All authenticated endpoints require:
```
Authorization: Bearer {token}
```

All responses follow the unified format:
```json
{
  "code": 200,
  "message": "Success",
  "data": { ... },
  "timestamp": 1773329279705
}
```

---

## Authentication

### Register

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "password123"
  }'
```

**Response `201 Created`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "userId": 1,
    "username": "testuser",
    "role": "USER",
    "accessToken": "eyJhbGciOiJIUzM4NCJ9...",
    "expiresIn": 86400000
  },
  "timestamp": 1773329279705
}
```

**Validation failure `400 Bad Request`:**
```json
{
  "code": 400,
  "message": "Password must be at least 8 characters",
  "timestamp": 1773329279705
}
```

**Duplicate username `400 Bad Request`:**
```json
{
  "code": 20002,
  "message": "Username 'testuser' is already taken",
  "timestamp": 1773329279705
}
```

---

### Login

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123"
  }'
```

**Response `200 OK`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "userId": 1,
    "username": "testuser",
    "role": "USER",
    "accessToken": "eyJhbGciOiJIUzM4NCJ9...",
    "expiresIn": 86400000
  },
  "timestamp": 1773329356653
}
```

**Wrong credentials `400 Bad Request`:**
```json
{
  "code": 20003,
  "message": "Invalid username or password",
  "timestamp": 1773329356653
}
```

> Note: The error message is intentionally vague — it does not reveal whether
> the username or password was wrong. This prevents username enumeration attacks.

---

## Events (Admin)

> All `/api/v1/admin/**` endpoints require `role: ADMIN`.
> Promote a user via: `UPDATE users SET role = 'ADMIN' WHERE username = 'admin';`

### Create Event

```bash
curl -X POST http://localhost:8080/api/v1/admin/events \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {admin_token}" \
  -d '{
    "name": "Taylor Swift Eras Tour Shanghai",
    "description": "The most iconic concert tour of the decade",
    "venue": "Shanghai National Stadium",
    "eventDate": "2026-08-15T19:00:00",
    "saleStartTime": "2026-04-01T10:00:00",
    "saleEndTime": "2026-08-15T12:00:00",
    "ticketTypes": [
      {
        "name": "VIP Floor",
        "price": 2888.00,
        "totalStock": 500
      },
      {
        "name": "Standard",
        "price": 988.00,
        "totalStock": 3000
      },
      {
        "name": "Economy",
        "price": 488.00,
        "totalStock": 5000
      }
    ]
  }'
```

**Response `201 Created`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "id": 1,
    "name": "Taylor Swift Eras Tour Shanghai",
    "description": "The most iconic concert tour of the decade",
    "venue": "Shanghai National Stadium",
    "eventDate": "2026-08-15T19:00:00",
    "saleStartTime": "2026-04-01T10:00:00",
    "saleEndTime": "2026-08-15T12:00:00",
    "status": "DRAFT",
    "onSale": false,
    "ticketTypes": [
      { "id": 1, "name": "VIP Floor",  "price": 2888.00, "totalStock": 500,  "availableStock": 500  },
      { "id": 2, "name": "Standard",   "price": 988.00,  "totalStock": 3000, "availableStock": 3000 },
      { "id": 3, "name": "Economy",    "price": 488.00,  "totalStock": 5000, "availableStock": 5000 }
    ],
    "createdAt": "2026-03-12T09:19:07"
  },
  "timestamp": 1773332347250
}
```

> Event is created in `DRAFT` status. It must be explicitly published before
> users can purchase tickets.

---

### Publish Event

```bash
curl -X PUT http://localhost:8080/api/v1/admin/events/1/publish \
  -H "Authorization: Bearer {admin_token}"
```

**Response `200 OK`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "id": 1,
    "status": "PUBLISHED",
    "onSale": false,
    ...
  },
  "timestamp": 1773332423094
}
```

> `onSale: false` because current time is before `saleStartTime`.
> `onSale` becomes `true` automatically once `saleStartTime` is reached —
> no additional admin action required.

**Attempt to publish an already-published event `400 Bad Request`:**
```json
{
  "code": 30002,
  "message": "Only DRAFT events can be published, current status: PUBLISHED",
  "timestamp": 1773332423094
}
```

---

### Cancel Event

```bash
curl -X PUT http://localhost:8080/api/v1/admin/events/1/cancel \
  -H "Authorization: Bearer {admin_token}"
```

**Response `200 OK`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "id": 1,
    "status": "CANCELLED",
    ...
  },
  "timestamp": 1773332500000
}
```

---

## Events (Public)

### Get Single Event

```bash
curl http://localhost:8080/api/v1/events/1
```

**Response `200 OK`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "id": 1,
    "name": "Taylor Swift Eras Tour Shanghai",
    "status": "PUBLISHED",
    "onSale": true,
    "ticketTypes": [
      { "id": 1, "name": "VIP Floor", "price": 2888.00, "totalStock": 500, "availableStock": 487 },
      { "id": 2, "name": "Standard",  "price": 988.00,  "totalStock": 3000, "availableStock": 2991 },
      { "id": 3, "name": "Economy",   "price": 488.00,  "totalStock": 5000, "availableStock": 5000 }
    ],
    ...
  }
}
```

**Event not found `400 Bad Request`:**
```json
{
  "code": 30001,
  "message": "Event #999 not found",
  "timestamp": 1773332500000
}
```

---

### List Published Events (Paginated)

```bash
curl "http://localhost:8080/api/v1/events?page=0&size=10"
```

**Response `200 OK`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "content": [ { "id": 1, "name": "Taylor Swift Eras Tour Shanghai", ... } ],
    "page": 0,
    "size": 10,
    "totalElements": 1,
    "totalPages": 1,
    "last": true
  },
  "timestamp": 1773332600000
}
```

---

### List Currently On-Sale Events

```bash
curl "http://localhost:8080/api/v1/events/on-sale?page=0&size=10"
```

Returns only events where `status = PUBLISHED` AND current time is within
`[saleStartTime, saleEndTime]`. Format identical to list above.

---

## Orders

> All order endpoints require authentication.

### Place Order

The `requestId` is an idempotency key. Generate a UUID on the client before
sending the request. Retrying with the same `requestId` returns the original
order without creating a duplicate.

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {user_token}" \
  -d '{
    "ticketTypeId": 2,
    "quantity": 2,
    "requestId": "550e8400-e29b-41d4-a716-446655440000"
  }'
```

**Response `201 Created`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "id": 1,
    "orderNo": "TF1773357772472378AAB",
    "userId": 1,
    "ticketTypeId": 2,
    "quantity": 2,
    "unitPrice": 988.00,
    "totalAmount": 1976.00,
    "status": "CREATED",
    "expiredAt": "2026-03-12T16:37:52",
    "createdAt": "2026-03-12T16:22:52"
  },
  "timestamp": 1773357772507
}
```

**Idempotency — same `requestId` returns same order:**
```bash
# Send the exact same request again
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {user_token}" \
  -d '{
    "ticketTypeId": 2,
    "quantity": 2,
    "requestId": "550e8400-e29b-41d4-a716-446655440000"
  }'
```

**Response: identical order, no duplicate created:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "id": 1,
    "orderNo": "TF1773357772472378AAB",
    ...
  }
}
```

**Event not on sale `400 Bad Request`:**
```json
{
  "code": 30002,
  "message": "Event 'Taylor Swift Eras Tour Shanghai' is not currently on sale",
  "timestamp": 1773357679057
}
```

**Insufficient stock `400 Bad Request`:**
```json
{
  "code": 30004,
  "message": "Tickets are sold out",
  "timestamp": 1773357679057
}
```

**High concurrency conflict (optimistic lock) `400 Bad Request`:**
```json
{
  "code": 30005,
  "message": "High demand — please try again",
  "timestamp": 1773357679057
}
```

---

### Cancel Order

```bash
curl -X POST http://localhost:8080/api/v1/orders/TF1773357772472378AAB/cancel \
  -H "Authorization: Bearer {user_token}"
```

**Response `200 OK`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "orderNo": "TF1773357772472378AAB",
    "status": "CANCELLED",
    ...
  },
  "timestamp": 1773357923674
}
```

**Cannot cancel a CONFIRMED order `400 Bad Request`:**
```json
{
  "code": 40006,
  "message": "Order in status [CONFIRMED] cannot be cancelled",
  "timestamp": 1773357923674
}
```

---

### Initiate Payment

Transitions order from `CREATED` to `PAYING`.

```bash
curl -X POST http://localhost:8080/api/v1/orders/TF17733580574812F5DEA/pay \
  -H "Authorization: Bearer {user_token}"
```

**Response `200 OK`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "orderNo": "TF17733580574812F5DEA",
    "status": "PAYING",
    ...
  },
  "timestamp": 1773358167655
}
```

**Order expired `400 Bad Request`:**
```json
{
  "code": 40005,
  "message": "Order has expired, please create a new order",
  "timestamp": 1773358167655
}
```

---

### Confirm Payment

Transitions order from `PAYING` → `PAID` → `CONFIRMED`.

In a real system, this endpoint is called by the payment gateway webhook after
verifying the payment signature. In MVP, it is a manual trigger for testing.

```bash
curl -X POST http://localhost:8080/api/v1/orders/TF17733580574812F5DEA/confirm-payment \
  -H "Authorization: Bearer {user_token}"
```

**Response `200 OK`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "orderNo": "TF17733580574812F5DEA",
    "status": "CONFIRMED",
    ...
  },
  "timestamp": 1773358201626
}
```

---

### Get Order

```bash
curl http://localhost:8080/api/v1/orders/TF17733580574812F5DEA \
  -H "Authorization: Bearer {user_token}"
```

> Users can only view their own orders. Querying another user's order returns
> `ORDER_NOT_FOUND` rather than `FORBIDDEN` — this prevents order number enumeration.

---

### List User Orders (Paginated)

```bash
curl "http://localhost:8080/api/v1/orders?page=0&size=10" \
  -H "Authorization: Bearer {user_token}"
```

**Response `200 OK`:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "content": [
      {
        "orderNo": "TF17733580574812F5DEA",
        "status": "CONFIRMED",
        "totalAmount": 1976.00,
        ...
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 2,
    "totalPages": 1,
    "last": true
  },
  "timestamp": 1773358300000
}
```

---

## Business Error Code Reference

| Code | Constant | Meaning |
|------|----------|---------|
| 200 | SUCCESS | Request succeeded |
| 400 | — | Validation failed (field-level error) |
| **User / Auth** | | |
| 20001 | USER_NOT_FOUND | User does not exist |
| 20002 | USER_ALREADY_EXISTS | Username or email already registered |
| 20003 | INVALID_CREDENTIALS | Wrong username or password |
| 20004 | TOKEN_INVALID | JWT signature invalid or malformed |
| 20005 | TOKEN_EXPIRED | JWT has expired |
| 20006 | TOKEN_MISSING | No Authorization header provided |
| **Event / Ticket** | | |
| 30001 | EVENT_NOT_FOUND | Event does not exist |
| 30002 | EVENT_NOT_AVAILABLE | Event not published or outside sale window |
| 30003 | TICKET_TYPE_NOT_FOUND | Ticket type does not exist |
| 30004 | INVENTORY_INSUFFICIENT | No tickets remaining |
| 30005 | INVENTORY_LOCK_FAILED | Concurrent conflict — retry recommended |
| 30006 | PURCHASE_LIMIT_EXCEEDED | Quantity exceeds per-order limit (max 4) |
| **Order** | | |
| 40001 | ORDER_NOT_FOUND | Order does not exist or belongs to another user |
| 40002 | ORDER_CREATE_FAILED | Order creation failed |
| 40003 | ORDER_STATUS_INVALID | Illegal state transition attempted |
| 40004 | ORDER_ALREADY_PAID | Order has already been paid |
| 40005 | ORDER_EXPIRED | Order payment window has passed |
| 40006 | ORDER_CANCEL_NOT_ALLOWED | Order status does not permit cancellation |
| 40007 | IDEMPOTENT_REJECTION | Duplicate request detected |
| **Payment** | | |
| 50001 | PAYMENT_FAILED | Payment processing failed |
| 50002 | PAYMENT_TIMEOUT | Payment gateway timed out |
| 50003 | PAYMENT_ALREADY_PROCESSED | Payment has already been applied |
| **System** | | |
| 99001 | INTERNAL_ERROR | Unexpected server error (details hidden for security) |
| 99002 | SERVICE_UNAVAILABLE | Service temporarily unavailable |

---

## Complete Purchase Flow (Script)

```bash
#!/bin/bash
BASE="http://localhost:8080"

# 1. Register user
echo "=== Register ==="
curl -s -X POST $BASE/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"buyer","email":"buyer@example.com","password":"password123"}' | jq .

# 2. Login and extract token
echo "=== Login ==="
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"buyer","password":"password123"}' | jq -r '.data.accessToken')
echo "Token: $TOKEN"

# 3. Browse events
echo "=== Browse Events ==="
curl -s "$BASE/api/v1/events?page=0&size=10" | jq .

# 4. Place order (ticketTypeId=2 = Standard ticket)
echo "=== Place Order ==="
REQUEST_ID=$(uuidgen)
ORDER_NO=$(curl -s -X POST $BASE/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"ticketTypeId\":2,\"quantity\":2,\"requestId\":\"$REQUEST_ID\"}" \
  | jq -r '.data.orderNo')
echo "Order: $ORDER_NO"

# 5. Initiate payment
echo "=== Pay ==="
curl -s -X POST "$BASE/api/v1/orders/$ORDER_NO/pay" \
  -H "Authorization: Bearer $TOKEN" | jq .

# 6. Confirm payment
echo "=== Confirm ==="
curl -s -X POST "$BASE/api/v1/orders/$ORDER_NO/confirm-payment" \
  -H "Authorization: Bearer $TOKEN" | jq .

# 7. View order
echo "=== Final Order State ==="
curl -s "$BASE/api/v1/orders/$ORDER_NO" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

> Requires `jq` and `uuidgen`. On macOS: `brew install jq`.
> On Ubuntu: `apt-get install jq uuid-runtime`.
