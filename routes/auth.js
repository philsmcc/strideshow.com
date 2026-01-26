const express = require("express");
const router = express.Router();
const passport = require("passport");
const bcrypt = require("bcryptjs");
const pool = require("../config/database");

// Google OAuth
router.get("/google", passport.authenticate("google", {
    scope: ["profile", "email"],
    
    
}));

router.get("/google/callback",
    passport.authenticate("google", { failureRedirect: "/login" }),
    (req, res) => {
        res.redirect("/dashboard");
    }
);

// Email/Password Registration
router.post("/register", async (req, res) => {
    try {
        const { email, password, name } = req.body;

        // Check if user exists
        const existingUser = await pool.query(
            "SELECT * FROM users WHERE email = $1",
            [email]
        );

        if (existingUser.rows.length > 0) {
            return res.render("register", { 
                user: null, 
                error: "Email already registered. Please login instead." 
            });
        }

        // Validate password
        if (!password || password.length < 8) {
            return res.render("register", { 
                user: null, 
                error: "Password must be at least 8 characters." 
            });
        }

        // Hash password
        const salt = await bcrypt.genSalt(10);
        const passwordHash = await bcrypt.hash(password, salt);

        // Create user
        const result = await pool.query(
            "INSERT INTO users (email, password_hash, name) VALUES ($1, $2, $3) RETURNING id, email, name",
            [email, passwordHash, name]
        );

        // Log the user in
        req.login(result.rows[0], (err) => {
            if (err) {
                console.error("Login after registration error:", err);
                return res.render("register", { 
                    user: null, 
                    error: "Account created but login failed. Please try logging in." 
                });
            }
            res.redirect("/dashboard");
        });
    } catch (err) {
        console.error("Registration error:", err);
        res.render("register", { 
            user: null, 
            error: "Registration failed. Please try again." 
        });
    }
});

// Email/Password Login
router.post("/login", async (req, res) => {
    console.log("Login attempt for:", req.body.email);
    
    try {
        const { email, password } = req.body;

        const result = await pool.query(
            "SELECT * FROM users WHERE email = $1",
            [email]
        );

        console.log("User found:", result.rows.length > 0);

        if (result.rows.length === 0) {
            return res.render("login", { 
                user: null, 
                error: "Invalid email or password." 
            });
        }

        const user = result.rows[0];

        if (!user.password_hash) {
            return res.render("login", { 
                user: null, 
                error: "This account uses Google sign-in. Please click 'Continue with Google'." 
            });
        }

        const isMatch = await bcrypt.compare(password, user.password_hash);
        console.log("Password match:", isMatch);

        if (!isMatch) {
            return res.render("login", { 
                user: null, 
                error: "Invalid email or password." 
            });
        }

        console.log("Attempting req.login for user:", user.id);
        
        req.login(user, (err) => {
            if (err) {
                console.error("Login error:", err);
                return res.render("login", { 
                    user: null, 
                    error: "Login failed. Please try again." 
                });
            }
            console.log("Login successful, redirecting to dashboard");
            return res.redirect("/dashboard");
        });
    } catch (err) {
        console.error("Login error:", err);
        res.render("login", { 
            user: null, 
            error: "Login failed. Please try again." 
        });
    }
});

// Logout
router.get("/logout", (req, res) => {
    req.logout((err) => {
        if (err) {
            console.error(err);
        }
        res.redirect("/");
    });
});

module.exports = router;
