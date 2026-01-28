// docs/benchmarks/scripts/fixtures.js
//
// Shared test fixture for all benchmarks. Creates an event, publishes it,
// and returns tokens + IDs. Prerequisites: app running, admin user exists,
// previous data cleaned.

import http from 'k6/http';
import { fail } from 'k6';

export const BASE_URL      = __ENV.BASE_URL   || 'http://localhost:8080';
export const INITIAL_STOCK = parseInt(__ENV.STOCK || '500');

const ADMIN_USER = __ENV.ADMIN_USER || 'loadtest_admin';
const ADMIN_PASS = __ENV.ADMIN_PASS || 'Admin123!';
const USER_NAME  = __ENV.USER_NAME  || 'loadtest_user';
const USER_PASS  = __ENV.USER_PASS  || 'LoadTest123!';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function login(username, password, label) {
    const res = http.post(`${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ username, password }),
        { headers: JSON_HEADERS }
    );
    if (res.status !== 200) {
        fail(`${label} login failed (${res.status}): ${res.body}`);
    }
    const token = JSON.parse(res.body).data?.accessToken;
    if (!token) fail(`${label} accessToken missing`);
    return token;
}

function createFixtureEvent(adminToken) {
    const now = new Date();
    const authHeaders = { ...JSON_HEADERS, Authorization: `Bearer ${adminToken}` };

    // saleStartTime in the past so the event is on sale immediately after publish
    const eventRes = http.post(`${BASE_URL}/api/v1/events`,
        JSON.stringify({
            name:          `Load Test Concert ${now.toISOString()}`,
            description:   'k6 load test fixture',
            venue:         'Test Arena',
            eventDate:     new Date(now.getTime() + 90 * 86400000).toISOString(),
            saleStartTime: new Date(now.getTime() -  2 * 86400000).toISOString(),
            saleEndTime:   new Date(now.getTime() + 60 * 86400000).toISOString(),
            ticketTypes:   [{ name: 'Standard', price: 99.00, totalStock: INITIAL_STOCK }],
        }),
        { headers: authHeaders }
    );
    if (eventRes.status !== 201) fail(`Event creation failed (${eventRes.status}): ${eventRes.body}`);

    const eventBody = JSON.parse(eventRes.body).data;
    const eventId = eventBody?.id;
    if (!eventId) fail(`eventId missing: ${eventRes.body}`);

    const publishRes = http.post(`${BASE_URL}/api/v1/events/${eventId}/publish`, null, { headers: authHeaders });
    if (publishRes.status !== 200) fail(`Publish failed (${publishRes.status}): ${publishRes.body}`);

    const ticketTypeId = eventBody?.ticketTypes?.[0]?.id;
    if (!ticketTypeId) fail(`ticketTypeId missing from create response: ${eventRes.body}`);

    return { eventId, ticketTypeId };
}

function ensureUser(username, password) {
    http.post(`${BASE_URL}/api/v1/auth/register`,
        JSON.stringify({ username, email: `${username}@test.com`, password }),
        { headers: JSON_HEADERS }
    );
}

export function prepareTestData() {
    const adminToken = login(ADMIN_USER, ADMIN_PASS, 'admin');
    const { eventId, ticketTypeId } = createFixtureEvent(adminToken);

    ensureUser(USER_NAME, USER_PASS);
    const userToken = login(USER_NAME, USER_PASS, 'user');

    console.log(`[setup] fixture ready: eventId=${eventId}, ticketTypeId=${ticketTypeId}, stock=${INITIAL_STOCK}`);
    return { ticketTypeId, userToken, adminToken, eventId };
}