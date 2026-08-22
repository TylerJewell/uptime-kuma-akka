import { akkaFeed } from "./akka-feed.js";
import { useToast } from "vue-toastification";
import jwtDecode from "jwt-decode";
import Favico from "favico.js";
import dayjs from "dayjs";
import mitt from "mitt";

import { DOWN, MAINTENANCE, PENDING, UP } from "../util.ts";
import {
    getDevContainerServerHostname,
    isDevContainer,
    getToastSuccessTimeout,
    getToastErrorTimeout,
} from "../util-frontend.js";
const toast = useToast();

// Every method below that this port does not serve still calls socket.emit(). The
// stand-in answers them the way the interface already handles a refusal, so a screen
// outside the slice degrades instead of throwing on a missing object.
const socket = {
    connected: true,
    emit(event, ...args) {
        const callback = args[args.length - 1];
        if (typeof callback === "function") {
            callback({ ok: false, msg: `${event} is outside this port's slice` });
        }
    },
    on() {},
    off() {},
    close() {},
};

const noSocketIOPages = [
    /^\/status-page$/, //  /status-page
    /^\/status/, // /status**
    /^\/$/, //  /
];

const favicon = new Favico({
    animation: "none",
});

export default {
    data() {
        return {
            info: {},
            socket: {
                token: null,
                firstConnect: true,
                connected: false,
                connectCount: 0,
                initedSocketIO: false,
            },
            username: null,
            remember: localStorage.remember !== "0",
            allowLoginDialog: false, // Allowed to show login dialog, but "loggedIn" have to be true too. This exists because prevent the login dialog show 0.1s in first before the socket server auth-ed.
            loggedIn: false,
            monitorList: {},
            monitorTypeList: {},
            maintenanceList: {},
            apiKeyList: {},
            heartbeatList: {},
            avgPingList: {},
            uptimeList: {},
            tlsInfoList: {},
            domainInfoList: {},
            notificationList: [],
            dockerHostList: [],
            remoteBrowserList: [],
            statusPageListLoaded: false,
            statusPageList: [],
            proxyList: [],
            connectionErrorMsg: `${this.$t("Cannot connect to the socket server.")} ${this.$t("Reconnecting...")}`,
            showReverseProxyGuide: true,
            cloudflared: {
                cloudflareTunnelToken: "",
                installed: null,
                running: false,
                message: "",
                errorMessage: "",
                currentPassword: "",
            },
            faviconUpdateDebounce: null,
            emitter: mitt(),
        };
    },

    created() {
        this.initSocketIO();
    },

    methods: {
        /**
         * Initialize connection to socket server
         * @param {boolean} bypass Should the check for if we
         * are on a status page be bypassed?
         * @returns {void}
         */
        initSocketIO() {
            if (this.socket.initedSocketIO) {
                return;
            }
            this.socket.initedSocketIO = true;

            // Authentication is outside this port's slice, so the interface is handed the
            // logged-in state it would otherwise wait for a server to grant.
            this.loggedIn = true;
            this.allowLoginDialog = false;
            this.username = "local";
            // The interface parses this as a semver and compares it against its own build's, so
            // it is the version of uptime-kuma this interface is a copy of. Reporting anything
            // else raises a version-mismatch banner about a comparison that has no meaning here.
            this.info = { version: "2.5.3", latestVersion: "2.5.3", primaryBaseURL: null, serverTimezone: dayjs.tz.guess(), serverTimezoneOffset: dayjs().format("Z") };

            akkaFeed({
                onMonitors: (monitors) => {
                    this.assignMonitorUrlParser(monitors);
                    this.monitorList = monitors;
                },
                onHistory: (beat) => {
                    this.recordBeat(beat);
                    // The events table is filled two ways, and a beat must reach it by
                    // exactly one of them: a screen already open is told, and a screen
                    // opened later reads what is held. The stand-in answers that read
                    // synchronously, so a beat cannot be counted by both.
                    if (beat.important) {
                        this.emitter.emit("newImportantHeartbeat", beat);
                    }
                },
                onBeat: (beat) => {
                    this.recordBeat(beat);

                    if (beat.important) {
                        const monitor = this.monitorList[beat.monitorID];
                        if (monitor !== undefined) {
                            if (beat.status === 0) {
                                toast.error(`[${monitor.name}] [DOWN] ${beat.msg}`, { timeout: getToastErrorTimeout() });
                            } else if (beat.status === 1) {
                                toast.success(`[${monitor.name}] [Up] ${beat.msg}`, { timeout: getToastSuccessTimeout() });
                            } else {
                                toast(`[${monitor.name}] ${beat.msg}`);
                            }
                        }
                        this.emitter.emit("newImportantHeartbeat", beat);
                    }
                },
                onConnected: () => {
                    this.socket.connected = true;
                    this.socket.connectCount++;
                    this.socket.firstConnect = false;
                    this.showReverseProxyGuide = false;
                },
                onDisconnected: () => {
                    this.socket.connected = false;
                    this.connectionErrorMsg = `${this.$t("Lost connection to the socket server.")} ${this.$t("Reconnecting...")}`;
                },
            });
        },

        /**
         * Hold one beat against its monitor, keeping the same window the source keeps.
         * @param {object} beat Heartbeat in uptime-kuma's own shape
         * @returns {void}
         */
        recordBeat(beat) {
            if (!(beat.monitorID in this.heartbeatList)) {
                this.heartbeatList[beat.monitorID] = [];
            }
            this.heartbeatList[beat.monitorID].push(beat);
            if (this.heartbeatList[beat.monitorID].length >= 150) {
                this.heartbeatList[beat.monitorID].shift();
            }
            this.recomputeDerived(beat.monitorID);
        },

        /**
         * Uptime and average ping for one monitor, from the beats already held.
         *
         * The source computes both server-side and pushes them as their own messages. This
         * port's feed carries heartbeats only, so the two are derived here from the same
         * beats the bars are drawn from — which is why an uptime figure covers the retained
         * window rather than the monitor's whole life. Declared in the README.
         * @param {number|string} monitorID Monitor the beats belong to
         * @returns {void}
         */
        recomputeDerived(monitorID) {
            const beats = this.heartbeatList[monitorID] || [];
            const counted = beats.filter((b) => b.status === 0 || b.status === 1);
            const up = counted.filter((b) => b.status === 1).length;
            const ratio = counted.length ? up / counted.length : 0;
            for (const period of [ "24", "720", "1y" ]) {
                this.uptimeList[`${monitorID}_${period}`] = ratio;
            }
            const pings = beats.map((b) => b.ping).filter((p) => typeof p === "number");
            this.avgPingList[monitorID] = pings.length
                ? Math.round(pings.reduce((a, b) => a + b, 0) / pings.length)
                : null;
        },
        /**
         * parse all urls from list.
         * @param {object} data Monitor data to modify
         * @returns {object} list
         */
        assignMonitorUrlParser(data) {
            Object.entries(data).forEach(([monitorID, monitor]) => {
                monitor.getUrl = () => {
                    try {
                        return new URL(monitor.url);
                    } catch (_) {
                        return null;
                    }
                };
            });
            return data;
        },

        /**
         * The storage currently in use
         * @returns {Storage} Current storage
         */
        storage() {
            return this.remember ? localStorage : sessionStorage;
        },

        /**
         * Get payload of JWT cookie
         * @returns {(object | undefined)} JWT payload
         */
        getJWTPayload() {
            const jwtToken = this.$root.storage().token;

            if (jwtToken && jwtToken !== "autoLogin") {
                return jwtDecode(jwtToken);
            }
            return undefined;
        },

        /**
         * Get current socket
         * @returns {Socket} Current socket
         */
        getSocket() {
            // The important-events table is this slice's own state — a beat is important or
            // it is not — so it is served from the beats already held rather than refused.
            // Everything else falls through to the stand-in.
            const held = this.heartbeatList;
            return {
                ...socket,
                emit: (event, ...args) => {
                    const callback = args[args.length - 1];
                    const important = () =>
                        Object.values(held)
                            .flat()
                            .filter((b) => b.important)
                            .sort((a, b) => (a.time < b.time ? 1 : -1));
                    if (event === "monitorImportantHeartbeatListCount") {
                        callback({ ok: true, count: important().length });
                    } else if (event === "monitorImportantHeartbeatListPaged") {
                        const [ , offset, count ] = args;
                        callback({ ok: true, data: important().slice(offset, offset + count) });
                    } else {
                        socket.emit(event, ...args);
                    }
                },
            };
        },

        /**
         * Apply translation to a message if possible
         * @param {string | {key: string, values: object}} msg Message to translate
         * @returns {string} Translated message
         */
        applyTranslation(msg) {
            if (msg != null && typeof msg === "object") {
                return this.$t(msg.key, msg.values);
            } else {
                return this.$t(msg);
            }
        },

        /**
         * Show success or error toast dependent on response status code
         * @param {{ok:boolean, msg: string, msgi18n: false} | {ok:boolean, msg: string|{key: string, values: object}, msgi18n: true}} res Response object
         * @returns {void}
         */
        toastRes(res) {
            if (res.msgi18n) {
                res.msg = this.applyTranslation(res.msg);
            }

            if (res.ok) {
                toast.success(res.msg);
            } else {
                toast.error(res.msg);
            }
        },

        /**
         * Show a success toast
         * @param {string} msg Message to show
         * @returns {void}
         */
        toastSuccess(msg) {
            toast.success(this.$t(msg));
        },

        /**
         * Show an error toast
         * @param {string} msg Message to show
         * @returns {void}
         */
        toastError(msg) {
            toast.error(this.$t(msg));
        },

        /**
         * Callback for login
         * @callback loginCB
         * @param {object} res Response object
         */

        /**
         * Send request to log user in
         * @param {string} username Username to log in with
         * @param {string} password Password to log in with
         * @param {string} token User token
         * @param {loginCB} callback Callback to call with result
         * @returns {void}
         */
        login(username, password, token, callback) {
            socket.emit(
                "login",
                {
                    username,
                    password,
                    token,
                },
                (res) => {
                    if (res.tokenRequired) {
                        callback(res);
                    }

                    if (res.ok) {
                        this.storage().token = res.token;
                        this.socket.token = res.token;
                        this.loggedIn = true;
                        this.username = this.getJWTPayload()?.username;

                        // Trigger Chrome Save Password
                        history.pushState({}, "");
                    }

                    callback(res);
                }
            );
        },

        /**
         * Log in using a token
         * @param {string} token Token to log in with
         * @returns {void}
         */
        loginByToken(token) {
            socket.emit("loginByToken", token, (res) => {
                this.allowLoginDialog = true;

                if (!res.ok) {
                    this.logout();
                } else {
                    this.loggedIn = true;
                    this.username = this.getJWTPayload()?.username;
                }
            });
        },

        /**
         * Log out of the web application
         * @returns {void}
         */
        logout() {
            socket.emit("logout", () => {});
            this.storage().removeItem("token");
            this.socket.token = null;
            this.loggedIn = false;
            this.username = null;
            this.clearData();
        },

        /**
         * Callback for general socket requests
         * @callback socketCB
         * @param {object} res Result of operation
         */
        /**
         * Prepare 2FA configuration
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        prepare2FA(callback) {
            socket.emit("prepare2FA", callback);
        },

        /**
         * Save the current 2FA configuration
         * @param {any} secret Unused
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        save2FA(secret, callback) {
            socket.emit("save2FA", callback);
        },

        /**
         * Disable 2FA for this user
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        disable2FA(callback) {
            socket.emit("disable2FA", callback);
        },

        /**
         * Verify the provided 2FA token
         * @param {string} token Token to verify
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        verifyToken(token, callback) {
            socket.emit("verifyToken", token, callback);
        },

        /**
         * Get current 2FA status
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        twoFAStatus(callback) {
            socket.emit("twoFAStatus", callback);
        },

        /**
         * Get list of monitors
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        getMonitorList(callback) {
            if (!callback) {
                callback = () => {};
            }
            socket.emit("getMonitorList", callback);
        },

        /**
         * Get list of maintenances
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        getMaintenanceList(callback) {
            if (!callback) {
                callback = () => {};
            }
            socket.emit("getMaintenanceList", callback);
        },

        /**
         * Send list of API keys
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        getAPIKeyList(callback) {
            if (!callback) {
                callback = () => {};
            }
            socket.emit("getAPIKeyList", callback);
        },

        /**
         * Add a monitor
         * @param {object} monitor Object representing monitor to add
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        add(monitor, callback) {
            socket.emit("add", monitor, callback);
        },

        /**
         * Adds a maintenance
         * @param {object} maintenance Maintenance to add
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        addMaintenance(maintenance, callback) {
            socket.emit("addMaintenance", maintenance, callback);
        },

        /**
         * Add monitors to maintenance
         * @param {number} maintenanceID Maintenance to modify
         * @param {number[]} monitors IDs of monitors to add
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        addMonitorMaintenance(maintenanceID, monitors, callback) {
            socket.emit("addMonitorMaintenance", maintenanceID, monitors, callback);
        },

        /**
         * Add status page to maintenance
         * @param {number} maintenanceID Maintenance to modify
         * @param {number} statusPages ID of status page to add
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        addMaintenanceStatusPage(maintenanceID, statusPages, callback) {
            socket.emit("addMaintenanceStatusPage", maintenanceID, statusPages, callback);
        },

        /**
         * Get monitors affected by maintenance
         * @param {number} maintenanceID Maintenance to read
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        getMonitorMaintenance(maintenanceID, callback) {
            socket.emit("getMonitorMaintenance", maintenanceID, callback);
        },

        /**
         * Get status pages where maintenance is shown
         * @param {number} maintenanceID Maintenance to read
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        getMaintenanceStatusPage(maintenanceID, callback) {
            socket.emit("getMaintenanceStatusPage", maintenanceID, callback);
        },

        /**
         * Delete monitor by ID
         * @param {number} monitorID ID of monitor to delete
         * @param {boolean} deleteChildren Whether to delete child monitors (for groups)
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        deleteMonitor(monitorID, deleteChildren, callback) {
            socket.emit("deleteMonitor", monitorID, deleteChildren, callback);
        },

        /**
         * Delete specified maintenance
         * @param {number} maintenanceID Maintenance to delete
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        deleteMaintenance(maintenanceID, callback) {
            socket.emit("deleteMaintenance", maintenanceID, callback);
        },

        /**
         * Add an API key
         * @param {object} key API key to add
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        addAPIKey(key, callback) {
            socket.emit("addAPIKey", key, callback);
        },

        /**
         * Delete specified API key
         * @param {int} keyID ID of key to delete
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        deleteAPIKey(keyID, callback) {
            socket.emit("deleteAPIKey", keyID, callback);
        },

        /**
         * Clear the heartbeat list
         * @returns {void}
         */
        clearData() {
            console.log("reset heartbeat list");
            this.heartbeatList = {};
        },

        /**
         * Upload the provided backup
         * @param {string} uploadedJSON JSON to upload
         * @param {string} importHandle Type of import. If set to
         * most data in database will be replaced
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        uploadBackup(uploadedJSON, importHandle, callback) {
            socket.emit("uploadBackup", uploadedJSON, importHandle, callback);
        },

        /**
         * Clear events for a specified monitor
         * @param {number} monitorID ID of monitor to clear
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        clearEvents(monitorID, callback) {
            socket.emit("clearEvents", monitorID, callback);
        },

        /**
         * Clear the heartbeats of a specified monitor
         * @param {number} monitorID Id of monitor to clear
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        clearHeartbeats(monitorID, callback) {
            socket.emit("clearHeartbeats", monitorID, callback);
        },

        /**
         * Clear all statistics
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        clearStatistics(callback) {
            socket.emit("clearStatistics", callback);
        },

        /**
         * Get monitor beats for a specific monitor in a time range
         * @param {number} monitorID ID of monitor to fetch
         * @param {number} period Time in hours from now
         * @param {socketCB} callback Callback for socket response
         * @returns {void}
         */
        getMonitorBeats(monitorID, period, callback) {
            socket.emit("getMonitorBeats", monitorID, period, callback);
        },

        /**
         * Retrieves monitor chart data.
         * @param {string} monitorID - The ID of the monitor.
         * @param {number} period - The time period for the chart data, in hours.
         * @param {socketCB} callback - The callback function to handle the chart data.
         * @returns {void}
         */
        getMonitorChartData(monitorID, period, callback) {
            socket.emit("getMonitorChartData", monitorID, period, callback);
        },
    },

    computed: {
        usernameFirstChar() {
            if (typeof this.username == "string" && this.username.length >= 1) {
                return this.username.charAt(0).toUpperCase();
            } else {
                return "🐻";
            }
        },

        lastHeartbeatList() {
            let result = {};

            for (let monitorID in this.heartbeatList) {
                let index = this.heartbeatList[monitorID].length - 1;
                result[monitorID] = this.heartbeatList[monitorID][index];
            }

            return result;
        },

        statusList() {
            let result = {};

            let unknown = {
                text: this.$t("Unknown"),
                color: "secondary",
            };

            for (let monitorID in this.lastHeartbeatList) {
                let lastHeartBeat = this.lastHeartbeatList[monitorID];

                if (!lastHeartBeat) {
                    result[monitorID] = unknown;
                } else if (lastHeartBeat.status === UP) {
                    result[monitorID] = {
                        text: this.$t("Up"),
                        color: "primary",
                    };
                } else if (lastHeartBeat.status === DOWN) {
                    result[monitorID] = {
                        text: this.$t("Down"),
                        color: "danger",
                    };
                } else if (lastHeartBeat.status === PENDING) {
                    result[monitorID] = {
                        text: this.$t("Pending"),
                        color: "warning",
                    };
                } else if (lastHeartBeat.status === MAINTENANCE) {
                    result[monitorID] = {
                        text: this.$t("statusMaintenance"),
                        color: "maintenance",
                    };
                } else {
                    result[monitorID] = unknown;
                }
            }

            return result;
        },

        stats() {
            let result = {
                active: 0,
                up: 0,
                down: 0,
                maintenance: 0,
                pending: 0,
                unknown: 0,
                pause: 0,
            };

            for (let monitorID in this.$root.monitorList) {
                let beat = this.$root.lastHeartbeatList[monitorID];
                let monitor = this.$root.monitorList[monitorID];

                if (monitor && !monitor.active) {
                    result.pause++;
                } else if (beat) {
                    result.active++;
                    if (beat.status === UP) {
                        result.up++;
                    } else if (beat.status === DOWN) {
                        result.down++;
                    } else if (beat.status === PENDING) {
                        result.pending++;
                    } else if (beat.status === MAINTENANCE) {
                        result.maintenance++;
                    } else {
                        result.unknown++;
                    }
                } else {
                    result.unknown++;
                }
            }

            return result;
        },

        /**
         *  Frontend Version
         *  It should be compiled to a static value while building the frontend.
         *  Please see ./config/vite.config.js, it is defined via vite.js
         * @returns {string} Current version
         */
        frontendVersion() {
            // eslint-disable-next-line no-undef
            return FRONTEND_VERSION;
        },

        /**
         * Are both frontend and backend in the same version?
         * @returns {boolean} The frontend and backend match?
         */
        isFrontendBackendVersionMatched() {
            if (!this.info.version) {
                return true;
            }
            return this.info.version === this.frontendVersion;
        },
    },

    watch: {
        // Update Badge
        "stats.down"(to, from) {
            if (to !== from) {
                if (this.faviconUpdateDebounce != null) {
                    clearTimeout(this.faviconUpdateDebounce);
                }
                this.faviconUpdateDebounce = setTimeout(() => {
                    favicon.badge(to);
                }, 1000);
            }
        },

        // Reload the SPA if the server version is changed.
        "info.version"(to, from) {
            if (from && from !== to) {
                window.location.reload();
            }
        },

        remember() {
            localStorage.remember = this.remember ? "1" : "0";
        },

        // Reconnect the socket io, if status-page to dashboard
        "$route.fullPath"(newValue, oldValue) {
            if (newValue) {
                for (let page of noSocketIOPages) {
                    if (newValue.match(page)) {
                        return;
                    }
                }
            }

            this.initSocketIO();
        },
    },
};
