// docs/benchmarks/scripts/cdc-crash-convergence.js
// CDC crash-convergence probe.
// STOCK=5000 required, default 500 depletes before the kill opportunity.
// Heartbeat uses console.warn; k6 suppresses console.log during local runs.
//
// Kill: Stop-Process -Name java -Force  (PowerShell)
//       kill $(lsof -ti:8080)           (Linux/macOS)

import http from 'k6/http';
import {fail, sleep} from 'k6';
import {Counter} from 'k6/metrics';
import {prepareTestData, INITIAL_STOCK} from './fixtures.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    scenarios: {
        crash_demo: {
            executor: 'constant-vus',
            vus: 5,
            duration: '60s',
        },
    },
    thresholds: {}, //connection errors post-kill are expected; don't fail the run
};

const ordersPlaced = new Counter('orders_placed');
const soldOut = new Counter('sold_out');
const connectionLost = new Counter('connection_lost');
const unexpectedErrors = new Counter('unexpected_errors');

//one flag per VU, each VU logs its own "APP IS DOWN" line.
//multiple simultaneous timestamps make the kill moment obvious in output.
let appDownLogged = false;
let vuUnexpectedSeen = 0;

export function setup() {
    if (INITIAL_STOCK < 2000) {
        fail(`STOCK=${INITIAL_STOCK} too low for crash demo; re-run with -e STOCK=5000`);
    }
    console.log(`CDC crash-convergence | STOCK=${INITIAL_STOCK} | kill after 8-10 HEARTBEATs`);
    return prepareTestData();
}

export default function (data) {
    const requestId = `crash-demo-${__VU}-${__ITER}-${Date.now()}`;
    const ts = new Date().toISOString().substring(11, 23);

    const res = http.post(`${BASE_URL}/api/v1/orders`,
        JSON.stringify({ticketTypeId: data.ticketTypeId, quantity: 1, requestId}),
        {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${data.userToken}`,
            },
            timeout: '3s',
        }
    );

    if (res.status === 201) {
        ordersPlaced.add(1);
        if (__VU === 1 && __ITER % 3 === 0) {
            console.warn(`[${ts}] HEARTBEAT iter=${__ITER} (kill now if you've seen 8-10)`);
        }
    } else if (res.status === 409) {
        soldOut.add(1);
        // stock depleted before kill,demo narrative broken, re-run with larger STOCK
        if (__VU === 1 && __ITER % 10 === 0) {
            console.warn(`[${ts}] SOLD OUT -- re-run with larger STOCK`);
        }
    } else if (res.status === 0 || res.error_code) {
        connectionLost.add(1);
        // *** markers: visual anchor for screenshot 2, not decoration.
        if (!appDownLogged) {
            console.warn(`*** [${ts}] VU=${__VU} APP IS DOWN ***`);
            appDownLogged = true;
        }
    } else {
        unexpectedErrors.add(1);
        if (vuUnexpectedSeen < 3) {
            console.warn(`[${ts}] VU=${__VU} unexpected status=${res.status}`);
            vuUnexpectedSeen++;
        }
    }
    sleep(0.2);
}

export function teardown(data) {
    const id = data.ticketTypeId;
    console.log(`
ticketTypeId: ${id}

1. Snapshot DB and Redis (before restart):
   docker exec ticketflow-postgres psql -U postgres -d ticketflow -c "SELECT available_stock, version FROM inventories WHERE ticket_type_id=${id};"
   docker exec ticketflow-redis redis-cli GET inventory:${id}
 
   DB version == orders_placed. Redis may lag DB -- that is the divergence to capture.
 
2. Restart app:
   ./mvnw spring-boot:run -pl app
   Watch for: "Found previous partition offset" and "[CDC] Upsert: inventory:${id} = N"
 
3. Re-snapshot. DB and Redis must agree.
 
4. Cleanup:
   docker exec -i ticketflow-postgres psql -U postgres -d ticketflow < docker/k6-cleanup.sql`);
}