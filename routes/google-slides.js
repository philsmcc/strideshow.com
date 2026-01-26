const express = require("express");
const router = express.Router();
const { google } = require("googleapis");
const pool = require("../config/database");

// Helper to get authenticated Google client
async function getGoogleClient(user) {
    const oauth2Client = new google.auth.OAuth2(
        process.env.GOOGLE_CLIENT_ID,
        process.env.GOOGLE_CLIENT_SECRET,
        `${process.env.BASE_URL}/auth/google/callback`
    );
    
    oauth2Client.setCredentials({
        access_token: user.google_access_token,
        refresh_token: user.google_refresh_token
    });
    
    // Check if token needs refresh
    if (user.google_token_expiry && new Date(user.google_token_expiry) < new Date()) {
        console.log("Token expired, refreshing...");
        try {
            const { credentials } = await oauth2Client.refreshAccessToken();
            // Update tokens in database
            await pool.query(
                `UPDATE users SET 
                    google_access_token = $1,
                    google_refresh_token = COALESCE($2, google_refresh_token),
                    google_token_expiry = $3,
                    updated_at = NOW()
                WHERE id = $4`,
                [
                    credentials.access_token,
                    credentials.refresh_token,
                    new Date(credentials.expiry_date),
                    user.id
                ]
            );
            oauth2Client.setCredentials(credentials);
        } catch (err) {
            console.error("Token refresh failed:", err);
            throw new Error("Google authentication expired. Please sign in again.");
        }
    }
    
    return oauth2Client;
}

// Middleware to check Google connection
function ensureGoogleConnected(req, res, next) {
    if (!req.isAuthenticated()) {
        return res.status(401).json({ error: "Not authenticated" });
    }
    if (!req.user.google_access_token) {
        return res.status(403).json({ 
            error: "Google account not connected",
            needsGoogleAuth: true 
        });
    }
    next();
}

// Helper to extract presentation ID from various URL formats
function extractPresentationId(input) {
    if (!input) return null;
    
    // Already just an ID (alphanumeric with dashes/underscores)
    if (/^[a-zA-Z0-9_-]+$/.test(input) && input.length > 20) {
        return input;
    }
    
    // Full URL formats:
    // https://docs.google.com/presentation/d/PRESENTATION_ID/edit
    // https://docs.google.com/presentation/d/PRESENTATION_ID/edit#slide=id.g123
    // https://docs.google.com/presentation/d/PRESENTATION_ID/present
    // https://docs.google.com/presentation/d/PRESENTATION_ID
    const urlMatch = input.match(/\/presentation\/d\/([a-zA-Z0-9_-]+)/);
    if (urlMatch) {
        return urlMatch[1];
    }
    
    return null;
}

// Get slides from a specific presentation (by ID or URL)
router.get("/presentation/:presentationId", ensureGoogleConnected, async (req, res) => {
    try {
        const presentationId = extractPresentationId(req.params.presentationId);
        
        if (!presentationId) {
            return res.status(400).json({ error: "Invalid presentation ID or URL" });
        }
        
        const auth = await getGoogleClient(req.user);
        const slides = google.slides({ version: "v1", auth });
        
        const presentation = await slides.presentations.get({
            presentationId: presentationId
        });
        
        const slideData = presentation.data.slides.map((slide, index) => {
            return {
                id: slide.objectId,
                index: index,
                notes: extractNotesFromSlide(slide)
            };
        });
        
        res.json({
            presentationId: presentationId,
            title: presentation.data.title,
            slideCount: slideData.length,
            slides: slideData
        });
    } catch (err) {
        console.error("Error getting presentation:", err);
        if (err.code === 404) {
            return res.status(404).json({ error: "Presentation not found. Make sure the presentation is shared with your Google account or is publicly accessible." });
        }
        if (err.code === 403) {
            return res.status(403).json({ error: "Access denied. Make sure you have permission to view this presentation." });
        }
        if (err.message.includes("authentication expired")) {
            return res.status(401).json({ error: err.message, needsGoogleAuth: true });
        }
        res.status(500).json({ error: "Failed to get presentation" });
    }
});

// Get slide thumbnail with auth
router.get("/presentation/:presentationId/slides/:slideId/thumbnail", ensureGoogleConnected, async (req, res) => {
    try {
        const presentationId = extractPresentationId(req.params.presentationId);
        
        if (!presentationId) {
            return res.status(400).json({ error: "Invalid presentation ID" });
        }
        
        const auth = await getGoogleClient(req.user);
        const slides = google.slides({ version: "v1", auth });
        
        const response = await slides.presentations.pages.getThumbnail({
            presentationId: presentationId,
            pageObjectId: req.params.slideId,
            "thumbnailProperties.mimeType": "PNG",
            "thumbnailProperties.thumbnailSize": "LARGE"
        });
        
        res.json({
            contentUrl: response.data.contentUrl,
            width: response.data.width,
            height: response.data.height
        });
    } catch (err) {
        console.error("Error getting thumbnail:", err);
        res.status(500).json({ error: "Failed to get thumbnail" });
    }
});

// Import Google Slides presentation as a StrideShow slideshow
router.post("/import", ensureGoogleConnected, async (req, res) => {
    try {
        const { url, title: customTitle } = req.body;
        
        if (!url) {
            return res.status(400).json({ error: "Please provide a Google Slides URL or presentation ID" });
        }
        
        const presentationId = extractPresentationId(url);
        
        if (!presentationId) {
            return res.status(400).json({ error: "Invalid Google Slides URL. Please paste a valid Google Slides link." });
        }
        
        const auth = await getGoogleClient(req.user);
        const slidesApi = google.slides({ version: "v1", auth });
        
        // Get the presentation
        let presentation;
        try {
            presentation = await slidesApi.presentations.get({
                presentationId: presentationId
            });
        } catch (err) {
            if (err.code === 404) {
                return res.status(404).json({ error: "Presentation not found. Check the URL and make sure you have access to this presentation." });
            }
            if (err.code === 403) {
                return res.status(403).json({ error: "Access denied. Make sure the presentation is shared with your Google account." });
            }
            throw err;
        }
        
        const title = customTitle || presentation.data.title;
        
        // Create a new slideshow in our database
        const slideshowResult = await pool.query(
            "INSERT INTO slideshows (user_id, title, google_presentation_id) VALUES ($1, $2, $3) RETURNING *",
            [req.user.id, title, presentationId]
        );
        
        const slideshow = slideshowResult.rows[0];
        
        // Import each slide
        const importedSlides = [];
        for (let i = 0; i < presentation.data.slides.length; i++) {
            const slide = presentation.data.slides[i];
            
            // Get thumbnail
            let thumbnailUrl = null;
            try {
                const thumbResponse = await slidesApi.presentations.pages.getThumbnail({
                    presentationId: presentationId,
                    pageObjectId: slide.objectId,
                    "thumbnailProperties.mimeType": "PNG",
                    "thumbnailProperties.thumbnailSize": "LARGE"
                });
                thumbnailUrl = thumbResponse.data.contentUrl;
            } catch (thumbErr) {
                console.error("Failed to get thumbnail for slide:", slide.objectId);
            }
            
            // Extract notes
            const notes = extractNotesFromSlide(slide);
            
            // Insert slide into our database
            const slideResult = await pool.query(
                `INSERT INTO slides (slideshow_id, slide_order, content_url, thumbnail_url, notes, google_slide_id)
                 VALUES ($1, $2, $3, $4, $5, $6) RETURNING *`,
                [slideshow.id, i + 1, thumbnailUrl, thumbnailUrl, notes, slide.objectId]
            );
            
            importedSlides.push(slideResult.rows[0]);
        }
        
        res.json({
            success: true,
            slideshow: slideshow,
            slidesImported: importedSlides.length,
            message: `Imported "${title}" with ${importedSlides.length} slides`
        });
    } catch (err) {
        console.error("Error importing presentation:", err);
        if (err.message.includes("authentication expired")) {
            return res.status(401).json({ error: err.message, needsGoogleAuth: true });
        }
        res.status(500).json({ error: "Failed to import presentation: " + (err.message || "Unknown error") });
    }
});

// Helper function to extract notes text from slide
function extractNotesText(notesPage) {
    if (!notesPage || !notesPage.pageElements) return "";
    
    let notes = "";
    for (const element of notesPage.pageElements) {
        if (element.shape && element.shape.text) {
            for (const textElement of element.shape.text.textElements || []) {
                if (textElement.textRun && textElement.textRun.content) {
                    notes += textElement.textRun.content;
                }
            }
        }
    }
    return notes.trim();
}

function extractNotesFromSlide(slide) {
    try {
        if (slide.slideProperties && slide.slideProperties.notesPage) {
            return extractNotesText(slide.slideProperties.notesPage);
        }
    } catch (err) {
        console.error("Error extracting notes:", err);
    }
    return "";
}

module.exports = router;
