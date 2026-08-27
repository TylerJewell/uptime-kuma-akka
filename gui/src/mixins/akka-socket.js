/**
 * Where the interface gets its data.
 *
 * uptime-kuma's own front end holds one long-lived socket to its own server, over which calls and
 * pushes both travel. This port replaces that transport and nothing else. What this module hands
 * back has the same three members the socket did — `on`, `emit` and `connected` — so every handler
 * and every call in `socket.js` is unchanged and the components, styling, routes and assets are the
 * original's.
 *
 * A call becomes a request. Its answer carries two things: what the callback is handed, and the
 * messages the server would have pushed as a consequence, which are dispatched to the same
 * handlers that were listening for them.
 *
 * What nobody asked for — a heartbeat, the figures beside it — arrives on one Server-Sent Events
 * connection. A browser reconnects that itself and sends `Last-Event-ID`, which the endpoint reads
 * as the position to resume from, so a dropped connection refills its own gap without the page
 * asking. Frames carry an event name: `history` for what the replay produced and `live` for what
 * happened after it, and only the second kind raises an alert.
 */

/**
 * Open the connection.
 * @param {string|undefined} url Where the server is, or undefined for wherever this page came from
 * @returns {object} An object with the same shape the socket had
 */
export function akkaSocket(url) {
    const base = url || "";
    const handlers = new Map();
    const socket = {
        connected: false,
        /**
         * Register a handler for one kind of message.
         * @param {string} name Message name
         * @param {Function} handler What to call with its arguments
         * @returns {void}
         */
        on(name, handler) {
            if (!handlers.has(name)) {
                handlers.set(name, []);
            }
            handlers.get(name).push(handler);
        },
        /**
         * Stop listening.
         * @param {string} name Message name
         * @returns {void}
         */
        off(name) {
            handlers.delete(name);
        },
        /**
         * Make a call.
         *
         * The last argument is the callback when it is a function, exactly as the socket treated
         * it, so no call site changes.
         * @param {string} event Call name
         * @param {...any} args Its arguments, with an optional callback last
         * @returns {void}
         */
        emit(event, ...args) {
            let callback = null;
            let payload = args;
            if (args.length > 0 && typeof args[args.length - 1] === "function") {
                callback = args[args.length - 1];
                payload = args.slice(0, -1);
            }
            call(event, payload).then((answer) => {
                dispatch(answer.emit);
                if (callback) {
                    callback(answer.result);
                }
            }).catch((error) => {
                if (callback) {
                    callback({ ok: false, msg: String(error) });
                }
            });
        },
        /**
         * Close the stream.
         * @returns {void}
         */
        close() {
            if (stream) {
                stream.close();
                stream = null;
            }
        },
    };

    let stream = null;

    /**
     * Hand a batch of messages to whoever is listening for each.
     * @param {Array} emissions Messages, each with a name and its arguments
     * @returns {void}
     */
    function dispatch(emissions) {
        for (const emission of emissions || []) {
            for (const handler of handlers.get(emission.name) || []) {
                handler(...(emission.args || []));
            }
        }
    }

    /**
     * The session token, read from where the interface already keeps it.
     * @returns {string|null} The token
     */
    function token() {
        try {
            return localStorage.token || sessionStorage.token || null;
        } catch (e) {
            return null;
        }
    }

    /**
     * Make one call.
     * @param {string} event Call name
     * @param {Array} args Its arguments
     * @returns {Promise<object>} The answer
     */
    async function call(event, args) {
        const response = await fetch(`${base}/socket/${encodeURIComponent(event)}`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ args, token: token() }),
        });
        if (!response.ok) {
            throw new Error(`${response.status}`);
        }
        return response.json();
    }

    /**
     * Open the stream of things nobody asked for, and keep it open.
     * @returns {void}
     */
    function subscribe() {
        if (stream) {
            return;
        }
        const suffix = token() ? `?token=${encodeURIComponent(token())}` : "";
        stream = new EventSource(`${base}/socket/stream${suffix}`);
        stream.addEventListener("history", (event) => {
            dispatch(JSON.parse(event.data).emit);
        });
        stream.addEventListener("live", (event) => {
            dispatch(JSON.parse(event.data).emit);
        });
        stream.addEventListener("open", () => {
            socket.connected = true;
            for (const handler of handlers.get("connect") || []) {
                handler();
            }
        });
        stream.addEventListener("error", () => {
            socket.connected = false;
            for (const handler of handlers.get("disconnect") || []) {
                handler();
            }
        });
    }

    // What the socket used to be told the moment it connected.
    fetch(`${base}/socket/hello`)
        .then((response) => response.json())
        .then((answer) => {
            socket.connected = true;
            dispatch(answer.emit);
            for (const handler of handlers.get("connect") || []) {
                handler();
            }
            subscribe();
        })
        .catch((error) => {
            for (const handler of handlers.get("connect_error") || []) {
                handler(error);
            }
        });

    return socket;
}
