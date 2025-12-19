import http from 'k6/http';
import { fail } from 'k6';

const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080';
const ADMIN_USER = __ENV.ADMIN_USER || 'loadtest_admin';
const ADMIN_PASS = __ENV.ADMIN_PASS || 'Admin123!';
const USER_NAME  = __ENV.USER_NAME  || 'loadtest_user';
const USER_PASS  = __ENV.USER_PASS  || 'LoadTest123!';

export const INITIAL_STOCK = parseInt(__ENV.STOCK || '500');

export function prepareTestData() {
    const jsonHeaders = { 'Content-Type': 'application/json' };
    const now = new Date();

    //admin must exist in DB before running;create via POST /api/v1/auth/register + manual role update in psql
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

    //saleStartTime in the past so the event is on sale immediately after publish
    const twoDaysAgo  = new Date(now.getTime() -  2 * 86400000).toISOString();
    const sixtyDays   = new Date(now.getTime() + 60 * 86400000).toISOString();
    const ninetyDays  = new Date(now.getTime() + 90 * 86400000).toISOString();

    // register is best-effort, if the user already exists from a previous run, login still succeeds
    const eventRes = http.post(`${BASE_URL}/api/v1/events`,
        JSON.stringify({
            name:          `Load Test Concert ${now.toISOString()}`,
            description:   'k6 load test fixture',
            venue:         'Test Arena',
            eventDate:     ninetyDays,
            saleStartTime: twoDaysAgo,
            saleEndTime:   sixtyDays,
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
    if (!eventId) fail(`eventId missing: ${eventRes.body}`);


    const publishRes = http.post(
        `${BASE_URL}/api/v1/events/${eventId}/publish`,
        null,
        { headers: adminHeaders }
    );

    if (publishRes.status !== 200) {
        fail(`Publish failed (status=${publishRes.status}): ${publishRes.body}`);
    }

    // ticketTypeId is not returned by POST /events; fetch event detail to get it
    const eventDetail = http.get(`${BASE_URL}/api/v1/events/${eventId}`);

    if (eventDetail.status !== 200) {
        fail(`Event detail failed (status=${eventDetail.status}): ${eventDetail.body}`);
    }

    const ticketTypeId = JSON.parse(eventDetail.body).data.ticketTypes[0].id;
    if (!ticketTypeId) fail(`ticketTypeId missing: ${eventDetail.body}`);

    http.post(`${BASE_URL}/api/v1/auth/register`,
        JSON.stringify({
            username: USER_NAME,
            email: `${USER_NAME}@test.com`,
            password: USER_PASS,
        }),
        { headers: jsonHeaders }
    );

    const userLoginRes = http.post(`${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ username: USER_NAME, password: USER_PASS }),
        { headers: jsonHeaders }
    );

    if (userLoginRes.status !== 200) {
        fail(`User login failed (status=${userLoginRes.status}): ${userLoginRes.body}`);
    }

    const userToken = JSON.parse(userLoginRes.body).data.accessToken;
    if (!userToken) fail('User accessToken not found');

    console.log(`[Setup] Ready. eventId=${eventId}, ticketTypeId=${ticketTypeId}, stock=${INITIAL_STOCK}`);

    return { ticketTypeId, userToken, adminToken, eventId };
}