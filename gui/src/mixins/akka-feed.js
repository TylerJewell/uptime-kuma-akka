/**
 * Where the interface gets its data.
 *
 * uptime-kuma's own front end talks socket.io to its own server. This port replaces that
 * transport and nothing else: the components, styling, routes and assets are the
 * original's, and this module hands them the same reactive shapes the socket.io mixin
 * used to fill — a monitor list keyed by id, and a list of heartbeats per monitor.
 *
 * Heartbeats arrive over one Server-Sent Events connection per monitor, opened at
 * sequence zero, so the first thing a connection delivers is the history and everything
 * after it is live. A browser reconnects an EventSource itself and sends `Last-Event-ID`,
 * which the endpoint reads as the position to resume from, so a dropped connection
 * refills its own gap without the page asking.
 *
 * Frames carry an event name: `history` for what the replay produced, `heartbeat` for
 * what happened after it. Only the second kind raises an alert.
 */

const BASE = (typeof window !== "undefined" && window.AKKA_BASE) || "http://localhost:9060";

/** uptime-kuma's status codes, in the order its own util.ts declares them. */
const STATUS = {
    DOWN: 0,
    UP: 1,
    PENDING: 2,
    MAINTENANCE: 3,
};

/**
 * Turn one announcement from the port into the heartbeat shape the components read.
 * @param {object} a Announcement as the endpoint serialises it
 * @returns {object} Heartbeat in uptime-kuma's own shape
 */
function toBeat(a) {
    return {
        monitorID: a.monitorId,
        status: STATUS[a.status],
        time: new Date(a.atEpochMillis).toISOString().replace("T", " ").replace("Z", ""),
        msg: a.message,
        ping: a.pingMillis === null || a.pingMillis === undefined ? null : a.pingMillis,
        important: a.important,
        duration: 0,
    };
}

/**
 * Turn one summary row into the monitor shape the components read.
 *
 * The shape is the original server's own, field for field, because the components read it
 * directly and an absent field is not an empty one — a monitor with no `path` takes the
 * details screen down on a `.slice` of undefined. Fields outside this port's slice carry
 * the value the original ships for a plain HTTP monitor.
 * @param {object} s Summary row from the port
 * @returns {object} Monitor in uptime-kuma's own shape
 */
function toMonitor(s) {
    return {
        id: s.id,
        name: s.id,
        description: null,
        path: [ s.id ],
        pathName: s.id,
        parent: null,
        childrenIDs: [],
        url: s.url,
        type: "http",
        subtype: null,
        method: "GET",
        hostname: null,
        port: null,
        active: s.active,
        forceInactive: false,
        maintenance: s.underMaintenance,
        interval: s.intervalSeconds,
        retryInterval: s.retryIntervalSeconds,
        maxretries: s.maxRetries,
        resendInterval: s.resendIntervalSeconds,
        timeout: 0,
        weight: 2000,
        keyword: null,
        invertKeyword: false,
        expiryNotification: false,
        domainExpiryNotification: false,
        ignoreTls: false,
        upsideDown: false,
        maxredirects: 0,
        accepted_statuscodes: [ "200-299" ],
        proxyId: null,
        notificationIDList: {},
        tags: [],
        includeSensitiveData: false,
        screenshot: null,
        pushToken: null,
        conditions: [],
    };
}

/**
 * Subscribe to the port. Returns a function that closes every connection opened.
 * @param {object} handlers onMonitors, onHistory, onBeat, onConnected, onDisconnected
 * @returns {Function} Closes the subscription
 */
export function akkaFeed(handlers) {
    const streams = new Map();

    /**
     * Open the heartbeat stream for one monitor, if it is not already open.
     * @param {string} id Monitor id
     * @returns {void}
     */
    function subscribe(id) {
        if (streams.has(id)) {
            return;
        }
        const source = new EventSource(`${BASE}/monitors/${encodeURIComponent(id)}/heartbeats/stream`);

        source.addEventListener("history", (event) => {
            handlers.onHistory(toBeat(JSON.parse(event.data)));
        });
        source.addEventListener("heartbeat", (event) => {
            handlers.onBeat(toBeat(JSON.parse(event.data)));
        });
        source.addEventListener("open", () => handlers.onConnected());
        source.addEventListener("error", () => handlers.onDisconnected());

        streams.set(id, source);
    }

    fetch(`${BASE}/monitors/`)
        .then((response) => response.json())
        .then((body) => {
            const monitors = {};
            for (const summary of body.monitors) {
                monitors[summary.id] = toMonitor(summary);
                subscribe(summary.id);
            }
            handlers.onMonitors(monitors);
            handlers.onConnected();
        })
        .catch(() => handlers.onDisconnected());

    return () => {
        for (const source of streams.values()) {
            source.close();
        }
        streams.clear();
    };
}
