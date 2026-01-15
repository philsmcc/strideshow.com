const express = require("express");
const router = express.Router();
const { ensureAuthenticated } = require("../middleware/auth");
const pool = require("../config/database");

// Dashboard home
router.get("/", ensureAuthenticated, async (req, res) => {
    try {
        // Get user slideshows with slide count
        const slideshows = await pool.query(
            `SELECT s.*, COUNT(sl.id) as slide_count 
             FROM slideshows s 
             LEFT JOIN slides sl ON s.id = sl.slideshow_id 
             WHERE s.user_id = $1 
             GROUP BY s.id 
             ORDER BY s.updated_at DESC`,
            [req.user.id]
        );

        res.render("dashboard", {
            user: req.user,
            slideshows: slideshows.rows
        });
    } catch (err) {
        console.error("Dashboard error:", err);
        res.status(500).render("error", { 
            user: req.user,
            error: "Failed to load dashboard",
            message: "There was a problem loading your dashboard. Please try again."
        });
    }
});

// Import presentation page (PDF upload)
router.get("/import", ensureAuthenticated, (req, res) => {
    res.render("import", { user: req.user });
});

// Keep old Google import route for backward compatibility (redirects to new import)
router.get("/import-google", ensureAuthenticated, (req, res) => {
    res.redirect("/dashboard/import");
});

// Create new slideshow
router.get("/create", ensureAuthenticated, (req, res) => {
    res.render("create-slideshow", { user: req.user });
});

// View/Edit slideshow
router.get("/slideshow/:id", ensureAuthenticated, async (req, res) => {
    try {
        const slideshow = await pool.query(
            "SELECT * FROM slideshows WHERE id = $1 AND user_id = $2",
            [req.params.id, req.user.id]
        );

        if (slideshow.rows.length === 0) {
            return res.status(404).render("error", { 
                user: req.user,
                error: "Slideshow not found",
                message: "The slideshow you're looking for doesn't exist or you don't have access to it."
            });
        }

        const slides = await pool.query(
            "SELECT * FROM slides WHERE slideshow_id = $1 ORDER BY slide_order ASC",
            [req.params.id]
        );

        res.render("slideshow", {
            user: req.user,
            slideshow: slideshow.rows[0],
            slides: slides.rows
        });
    } catch (err) {
        console.error("Slideshow error:", err);
        res.status(500).render("error", { 
            user: req.user,
            error: "Failed to load slideshow",
            message: "There was a problem loading this slideshow. Please try again."
        });
    }
});

module.exports = router;
