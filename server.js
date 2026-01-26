require("dotenv").config();
const express = require("express");
const session = require("express-session");
const pgSession = require("connect-pg-simple")(session);
const pool = require("./config/database");
const passport = require("passport");
const helmet = require("helmet");
const cors = require("cors");
const cookieParser = require("cookie-parser");
const path = require("path");
const http = require("http");
const WebSocket = require("ws");
const { v4: uuidv4 } = require("uuid");
const QRCode = require("qrcode");
require("./config/passport");

const app = express();
app.set("trust proxy", 1);
const server = http.createServer(app);

// WebSocket server
const wss = new WebSocket.Server({ server, path: "/ws" });

// In-memory store for active screens and connections
const activeScreens = new Map(); // screenCode -> { screenId, ws, presenters: Set<ws> }
const screensByWs = new Map(); // ws -> screenCode

// Generate 6-character code (avoiding confusing characters)
function generateScreenCode() {
    const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let code = "";
    for (let i = 0; i < 6; i++) {
        code += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return code;
}

// WebSocket connection handling
wss.on("connection", (ws) => {
    console.log("New WebSocket connection");

    ws.on("message", async (message) => {
        try {
            const data = JSON.parse(message);
            console.log("WS Message:", data.type, "Data:", JSON.stringify(data).substring(0, 200));

            switch (data.type) {
                case "register-display":
                    await handleRegisterDisplay(ws, data);
                    break;
                case "connect-presenter":
                    await handleConnectPresenter(ws, data);
                    break;
                case "start-slideshow":
                    handleStartSlideshow(ws, data);
                    break;
                case "change-slide":
                    handleChangeSlide(ws, data);
                    break;
                case "end-slideshow":
                    handleEndSlideshow(ws, data);
                    break;
                case "laser-pointer":
                    handleLaserPointer(ws, data);
                    break;
                case "laser-pointer-off":
                    handleLaserPointerOff(ws, data);
                    break;
                case "disconnect-presenter":
                    handleDisconnectPresenter(ws, data);
                    break;
            }
        } catch (error) {
            console.error("WebSocket message error:", error);
            ws.send(JSON.stringify({ type: "error", message: "Invalid message format" }));
        }
    });

    ws.on("close", () => {
        handleDisconnect(ws);
    });

    ws.on("error", (error) => {
        console.error("WebSocket error:", error);
    });
});

async function handleRegisterDisplay(ws, data) {
    const { screenId } = data;
    let screenCode;
    let attempts = 0;

    // Generate unique code
    do {
        screenCode = generateScreenCode();
        attempts++;
    } while (activeScreens.has(screenCode) && attempts < 10);

    try {
        // Store in database
        await pool.query(
            `INSERT INTO display_screens (screen_code, screen_id, is_active, expires_at)
             VALUES ($1, $2, true, NOW() + INTERVAL '24 hours')
             ON CONFLICT (screen_id) DO UPDATE SET screen_code = $1, is_active = true, expires_at = NOW() + INTERVAL '24 hours'`,
            [screenCode, screenId]
        );

        // Store in memory
        activeScreens.set(screenCode, {
            screenId,
            ws,
            presenters: new Set()
        });
        screensByWs.set(ws, screenCode);

        // Generate QR code
        const connectUrl = `${process.env.BASE_URL || "https://www.ripslide.net"}/connect/${screenCode}`;
        const qrCodeDataUrl = await QRCode.toDataURL(connectUrl, {
            width: 200,
            margin: 2,
            color: { dark: "#000000", light: "#ffffff" }
        });

        ws.send(JSON.stringify({
            type: "display-registered",
            screenCode,
            qrCodeUrl: qrCodeDataUrl,
            connectUrl
        }));

        console.log("Display registered with code:", screenCode);
        console.log("Active screens:", Array.from(activeScreens.keys()));
    } catch (error) {
        console.error("Error registering display:", error);
        ws.send(JSON.stringify({ type: "error", message: "Failed to register display" }));
    }
}

async function handleConnectPresenter(ws, data) {
    const { screenCode, userId, userName } = data;
    
    console.log("Presenter connecting to screen:", screenCode);
    console.log("Active screens:", Array.from(activeScreens.keys()));

    const screen = activeScreens.get(screenCode);
    if (!screen) {
        console.log("Screen not found:", screenCode);
        ws.send(JSON.stringify({ type: "error", message: "Screen not found or expired" }));
        return;
    }

    // Add presenter to screen
    screen.presenters.add(ws);
    screensByWs.set(ws, screenCode);

    // Update database
    try {
        await pool.query(
            "UPDATE display_screens SET connected_user_id = $1 WHERE screen_code = $2",
            [userId, screenCode]
        );
    } catch (error) {
        console.error("Error updating display screen:", error);
    }

    // Notify display that presenter connected
    if (screen.ws && screen.ws.readyState === WebSocket.OPEN) {
        screen.ws.send(JSON.stringify({
            type: "presenter-connected",
            presenterName: userName
        }));
        console.log("Notified display of presenter connection");
    } else {
        console.log("Display WebSocket not available or not open");
    }

    // Confirm to presenter
    ws.send(JSON.stringify({
        type: "presenter-connected",
        screenCode
    }));

    console.log("Presenter", userName, "connected to screen", screenCode);
}

function handleStartSlideshow(ws, data) {
    console.log("handleStartSlideshow called with:", JSON.stringify(data));
    const { screenCode, slideshowId, slide } = data;

    const screen = activeScreens.get(screenCode);
    if (!screen || !screen.ws) {
        console.log("Screen not found or no ws:", screenCode);
        return;
    }

    // Send to display
    if (screen.ws.readyState === WebSocket.OPEN) {
        const payload = {
            type: "slideshow-started",
            slideshowId,
            slide
        };
        console.log("Sending to display:", JSON.stringify(payload));
        screen.ws.send(JSON.stringify(payload));
    }
    console.log("Slideshow", slideshowId, "started on screen", screenCode);
}

function handleChangeSlide(ws, data) {
    console.log("handleChangeSlide called with:", JSON.stringify(data));
    const { screenCode, slideIndex, slide } = data;

    const screen = activeScreens.get(screenCode);
    if (!screen || !screen.ws) {
        console.log("Screen not found or no ws for change:", screenCode);
        return;
    }

    // Send to display
    if (screen.ws.readyState === WebSocket.OPEN) {
        const payload = {
            type: "slide-changed",
            slideIndex,
            slide
        };
        console.log("Sending slide change to display:", JSON.stringify(payload));
        screen.ws.send(JSON.stringify(payload));
    }
}

function handleEndSlideshow(ws, data) {
    const { screenCode } = data;

    const screen = activeScreens.get(screenCode);
    if (!screen || !screen.ws) {
        console.log("Screen not found or no ws for end:", screenCode);
        return;
    }

    // Send to display
    if (screen.ws.readyState === WebSocket.OPEN) {
        screen.ws.send(JSON.stringify({
            type: "slideshow-ended"
        }));
    }
    console.log("Slideshow ended on screen", screenCode);
}

function handleLaserPointer(ws, data) {
    const { screenCode, x, y } = data;

    const screen = activeScreens.get(screenCode);
    if (!screen || !screen.ws) {
        return;
    }

    // Forward laser pointer position to display
    if (screen.ws.readyState === WebSocket.OPEN) {
        screen.ws.send(JSON.stringify({
            type: "laser-pointer",
            x,
            y
        }));
    }
}

function handleLaserPointerOff(ws, data) {
    const { screenCode } = data;

    const screen = activeScreens.get(screenCode);
    if (!screen || !screen.ws) {
        return;
    }

    // Tell display to hide laser pointer
    if (screen.ws.readyState === WebSocket.OPEN) {
        screen.ws.send(JSON.stringify({
            type: "laser-pointer-off"
        }));
    }
}

function handleDisconnectPresenter(ws, data) {
    const { screenCode } = data;

    const screen = activeScreens.get(screenCode);
    if (screen) {
        screen.presenters.delete(ws);

        // Notify display
        if (screen.ws && screen.ws.readyState === WebSocket.OPEN) {
            screen.ws.send(JSON.stringify({
                type: "presenter-disconnected"
            }));
        }
    }
    screensByWs.delete(ws);
    console.log("Presenter disconnected from screen", screenCode);
}

function handleDisconnect(ws) {
    const screenCode = screensByWs.get(ws);
    if (!screenCode) return;

    const screen = activeScreens.get(screenCode);
    if (!screen) return;

    if (screen.ws === ws) {
        // Display disconnected
        // Notify all presenters
        screen.presenters.forEach((presenterWs) => {
            if (presenterWs.readyState === WebSocket.OPEN) {
                presenterWs.send(JSON.stringify({ type: "display-disconnected" }));
            }
        });

        // Clean up
        activeScreens.delete(screenCode);

        // Mark as inactive in database
        pool.query("UPDATE display_screens SET is_active = false WHERE screen_code = $1", [screenCode])
            .catch(err => console.error("Error marking screen inactive:", err));

        console.log("Display", screenCode, "disconnected");
    } else {
        // Presenter disconnected
        screen.presenters.delete(ws);

        // Notify display
        if (screen.ws && screen.ws.readyState === WebSocket.OPEN) {
            screen.ws.send(JSON.stringify({
                type: "presenter-disconnected"
            }));
        }

        console.log("Presenter disconnected from screen", screenCode);
    }

    screensByWs.delete(ws);
}

// Middleware
app.use(helmet({
    contentSecurityPolicy: {
        directives: {
            defaultSrc: ["'self'"],
            scriptSrc: ["'self'", "'unsafe-inline'", "https://cdn.tailwindcss.com"],
            styleSrc: ["'self'", "'unsafe-inline'", "https://fonts.googleapis.com"],
            fontSrc: ["'self'", "https://fonts.gstatic.com"],
            imgSrc: ["'self'", "data:", "https:", "blob:"],
            mediaSrc: ["'self'", "https://strideshow-content.s3.us-west-2.amazonaws.com", "blob:"],
            connectSrc: ["'self'", "wss:", "ws:"],
            frameSrc: ["'self'"],
            scriptSrcAttr: ["'unsafe-inline'"],
        }
    }
}));
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(cookieParser());
app.use(express.static(path.join(__dirname, "public")));

// Session configuration
app.use(session({
    store: new pgSession({
        pool: pool,
        tableName: "session",
        createTableIfMissing: true
    }),
    secret: process.env.SESSION_SECRET || "your-secret-key",
    resave: false,
    saveUninitialized: false,
    cookie: {
        sameSite: "lax",
        secure: process.env.NODE_ENV === "production",
        maxAge: 7 * 24 * 60 * 60 * 1000 // 7 days
    }
}));

// Passport initialization
app.use(passport.initialize());
app.use(passport.session());

// View engine
app.set("view engine", "ejs");
app.set("views", path.join(__dirname, "views"));

// Make user available in all views
app.use((req, res, next) => {
    res.locals.user = req.user || null;
    next();
});

// Routes
app.use("/", require("./routes/index"));
app.use("/auth", require("./routes/auth"));
app.use("/api", require("./routes/api"));
app.use("/dashboard", require("./routes/dashboard"));
app.use("/api/google", require("./routes/google-slides"));
app.use("/api/pdf", require("./routes/pdf-import"));

// 404 handler
app.use((req, res) => {
    res.status(404).render("error", {
        user: req.user,
        error: "404 - Page Not Found",
        message: "The page you are looking for does not exist."
    });
});

// Error handler
app.use((err, req, res, next) => {
    console.error(err.stack);
    res.status(500).render("error", {
        user: req.user,
        error: "Something went wrong",
        message: "An unexpected error occurred. Please try again."
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log("Server running on port", PORT);
});
