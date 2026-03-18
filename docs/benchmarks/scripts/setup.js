// docs/benchmarks/scripts/setup.js
//
// Shared setup logic for all TicketFlow load test scripts.
//
// PRE-REQUISITE — create an ADMIN user before running any k6 script:
//
//   Step 1: Register the user (PowerShell):
//     Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/auth/register" `
//       -ContentType "application/json" `
//       -Body '{"username":"loadtest_admin","email":"admin@test.com","password":"Admin123!"}'
//
//   Step 2: Grant ADMIN role:
//     docker exec -it ticketflow-postgres psql -U postgres -d ticketflow `
//       -c "UPDATE users SET role='ADMIN' WHERE username='loadtest_admin';"

import http from 'k6/http';
import { fail } from 'k6';

const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080';
const ADMIN_USER = __ENV.ADMIN_USER || 'loadtest_admin';
const ADMIN_PASS = __ENV.ADMIN_PASS || 'Admin123!';
const USER_NAME  = __ENV.USER_NAME  || 'loadtest_user';
const USER_PASS  = __ENV.USER_PASS  || 'LoadTest123!';

export const INITIAL_STOCK = 200000;

/**
 * Returns a LocalDateTime string safe for Spring deserialization.
 *
 * Why not use toISOString()?
 * toISOString() returns UTC time with a "Z" suffix (e.g. "2026-03-18T16:36:14.000Z").
 * After stripping ".000Z" we get "2026-03-18T16:36:14". Spring stores this as
 * LocalDateTime without timezone info. But LocalDateTime.now() on the server uses
 * the server's local timezone. If the server is UTC-7, it's 09:36 locally while
 * we sent 16:36 — the event appears "not on sale" for 7 hours.
 *
 * Fix: add offsetMinutes to shift the UTC time into the server's local time.
 * Default assumes UTC-7 (US Pacific). Override via TIMEZONE_OFFSET_MINUTES env var:
 *   k6 run -e TIMEZONE_OFFSET_MINUTES=-420 script.js   (UTC-7, default)
 *   k6 run -e TIMEZONE_OFFSET_MINUTES=0   script.js   (UTC)
 *
 * Simpler alternative used here: set saleStart 2 days in the past and saleEnd
 * 60 days in the future. Any timezone offset (max ±14 hours) is dwarfed by
 * the 2-day / 60-day margins, making timezone errors impossible.
 */
function toLocalDateTime(date) {
    // Format as "YYYY-MM-DDTHH:mm:ss" — no timezone suffix.
    // Spring's LocalDateTime deserializer requires this exact format.
    const pad = n => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}` +
           `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export function prepareTestData() {
    const jsonHeaders = { 'Content-Type': 'application/json' };
    const now = new Date();

    // ── 1. Login as pre-created ADMIN ────────────────────────────────────────
    const adminLoginRes = http.post(`${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ username: ADMIN_USER, password: ADMIN_PASS }),
        { headers: jsonHeaders }
    );

    if (adminLoginRes.status !== 200) {
        fail(`Admin login failed (status=${adminLoginRes.status}). `
           + `Did you create the admin user and grant ADMIN role? `
           + `Body: ${adminLoginRes.body}`);
    }

    const adminToken = JSON.parse(adminLoginRes.body).data.accessToken;
    if (!adminToken) fail('Admin accessToken not found in login response');

    const adminHeaders = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${adminToken}`,
    };

    // ── 2. Create event with timezone-safe sale window ────────────────────────
    // saleStart = 2 days ago, saleEnd = 60 days later.
    // This margin (2 days / 60 days) is far larger than any timezone offset,
    // so isOnSale() returns true regardless of server timezone.
    const twoDaysAgo  = new Date(now.getTime() - 2  * 24 * 60 * 60 * 1000);
    const sixtyDays   = new Date(now.getTime() + 60 * 24 * 60 * 60 * 1000);
    const thirtyDays  = new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000);

    const eventRes = http.post(`${BASE_URL}/api/v1/admin/events`,
        JSON.stringify({
            name:          `Load Test Concert ${now.toISOString()}`,
            description:   'k6 load test fixture',
            venue:         'Test Arena',
            eventDate:     toLocalDateTime(thirtyDays),
            saleStartTime: toLocalDateTime(twoDaysAgo),
            saleEndTime:   toLocalDateTime(sixtyDays),
            ticketTypes: [{
                name:       'Standard',
                price:      99.00,
                totalStock: INITIAL_STOCK,
            }],
        }),
        { headers: adminHeaders }
    );

    if (eventRes.status !== 201) {
        fail(`Event creation failed (status=${eventRes.status}): ${eventRes.body}`);
    }

    const eventId = JSON.parse(eventRes.body).data.id;
    if (!eventId) fail(`eventId missing from response: ${eventRes.body}`);

    // ── 3. Publish event ──────────────────────────────────────────────────────
    const publishRes = http.put(
        `${BASE_URL}/api/v1/admin/events/${eventId}/publish`,
        null,
        { headers: adminHeaders }
    );

    if (publishRes.status !== 200) {
        fail(`Event publish failed (status=${publishRes.status}): ${publishRes.body}`);
    }

    // ── 4. Extract ticketTypeId ───────────────────────────────────────────────
    const eventDetailRes = http.get(
        `${BASE_URL}/api/v1/events/${eventId}`,
        { headers: { 'Authorization': `Bearer ${adminToken}` } }
    );

    if (eventDetailRes.status !== 200) {
        fail(`Event detail fetch failed (status=${eventDetailRes.status}): ${eventDetailRes.body}`);
    }

    const ticketTypeId = JSON.parse(eventDetailRes.body).data.ticketTypes[0].id;
    if (!ticketTypeId) fail(`ticketTypeId missing from event detail: ${eventDetailRes.body}`);

    // ── 5. Register regular user (201 = new, 400 code 20002 = already exists) ─
    http.post(`${BASE_URL}/api/v1/auth/register`,
        JSON.stringify({ username: USER_NAME, email: `${USER_NAME}@test.com`, password: USER_PASS }),
        { headers: jsonHeaders }
    );

    // ── 6. Login as regular user ──────────────────────────────────────────────
    const userLoginRes = http.post(`${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ username: USER_NAME, password: USER_PASS }),
        { headers: jsonHeaders }
    );

    if (userLoginRes.status !== 200) {
        fail(`User login failed (status=${userLoginRes.status}): ${userLoginRes.body}`);
    }

    const userToken = JSON.parse(userLoginRes.body).data.accessToken;
    if (!userToken) fail('User accessToken not found in login response');

    console.log(`[Setup] Ready. eventId=${eventId}, ticketTypeId=${ticketTypeId}, stock=${INITIAL_STOCK}`);

    return { ticketTypeId, userToken };
}
