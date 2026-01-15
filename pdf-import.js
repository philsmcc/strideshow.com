const express = require("express");
const router = express.Router();
const multer = require("multer");
const path = require("path");
const fs = require("fs").promises;
const { exec } = require("child_process");
const { promisify } = require("util");
const pool = require("../config/database");

const execAsync = promisify(exec);

// Configure multer for PDF uploads
const storage = multer.diskStorage({
    destination: async (req, file, cb) => {
        const uploadDir = path.join(__dirname, "../public/uploads/pdf");
        try {
            await fs.mkdir(uploadDir, { recursive: true });
            cb(null, uploadDir);
        } catch (err) {
            cb(err);
        }
    },
    filename: (req, file, cb) => {
        const uniqueSuffix = Date.now() + "-" + Math.round(Math.random() * 1e9);
        cb(null, uniqueSuffix + "-" + file.originalname.replace(/[^a-zA-Z0-9.-]/g, "_"));
    }
});

const upload = multer({
    storage: storage,
    limits: {
        fileSize: 100 * 1024 * 1024 // 100MB max
    },
    fileFilter: (req, file, cb) => {
        if (file.mimetype === "application/pdf") {
            cb(null, true);
        } else {
            cb(new Error("Only PDF files are allowed"));
        }
    }
});

// Middleware to ensure user is authenticated
function ensureAuthenticated(req, res, next) {
    if (req.isAuthenticated()) {
        return next();
    }
    res.status(401).json({ error: "Not authenticated" });
}

// Upload and process PDF
router.post("/upload", ensureAuthenticated, upload.single("pdf"), async (req, res) => {
    if (!req.file) {
        return res.status(400).json({ error: "No PDF file uploaded" });
    }

    const pdfPath = req.file.path;
    const title = req.body.title || path.basename(req.file.originalname, ".pdf");
    
    // Create output directory for this PDF's images
    const outputDir = path.join(__dirname, "../public/uploads/slides", Date.now().toString());
    
    try {
        await fs.mkdir(outputDir, { recursive: true });
        
        // Get page count first
        const { stdout: pageCountOutput } = await execAsync(`pdfinfo "${pdfPath}" | grep Pages | awk '{print $2}'`);
        const pageCount = parseInt(pageCountOutput.trim()) || 0;
        
        if (pageCount === 0) {
            throw new Error("Could not determine PDF page count or PDF is empty");
        }
        
        console.log(`Processing PDF with ${pageCount} pages`);
        
        // Convert PDF pages to PNG images using pdftoppm
        // -png: output PNG format
        // -r 150: 150 DPI (good balance of quality vs size)
        // -cropbox: use crop box to avoid extra margins
        await execAsync(`pdftoppm -png -r 150 -cropbox "${pdfPath}" "${outputDir}/slide"`);
        
        // Get list of generated images
        const files = await fs.readdir(outputDir);
        const slideImages = files
            .filter(f => f.endsWith(".png"))
            .sort((a, b) => {
                // Sort by page number (slide-1.png, slide-2.png, etc.)
                const numA = parseInt(a.match(/\d+/)?.[0] || "0");
                const numB = parseInt(b.match(/\d+/)?.[0] || "0");
                return numA - numB;
            });
        
        if (slideImages.length === 0) {
            throw new Error("Failed to convert PDF to images");
        }
        
        // Create slideshow in database
        const slideshowResult = await pool.query(
            "INSERT INTO slideshows (user_id, title) VALUES ($1, $2) RETURNING *",
            [req.user.id, title]
        );
        const slideshow = slideshowResult.rows[0];
        
        // Insert each slide into database
        const importedSlides = [];
        for (let i = 0; i < slideImages.length; i++) {
            const imageName = slideImages[i];
            const imageUrl = `/uploads/slides/${path.basename(outputDir)}/${imageName}`;
            
            const slideResult = await pool.query(
                `INSERT INTO slides (slideshow_id, slide_order, content_url, thumbnail_url)
                 VALUES ($1, $2, $3, $3) RETURNING *`,
                [slideshow.id, i + 1, imageUrl]
            );
            importedSlides.push(slideResult.rows[0]);
        }
        
        // Clean up the original PDF to save space
        await fs.unlink(pdfPath).catch(() => {});
        
        res.json({
            success: true,
            slideshow: slideshow,
            slidesImported: importedSlides.length,
            message: `Imported "${title}" with ${importedSlides.length} slides`
        });
        
    } catch (err) {
        console.error("PDF import error:", err);
        
        // Clean up on error
        await fs.unlink(pdfPath).catch(() => {});
        await fs.rm(outputDir, { recursive: true, force: true }).catch(() => {});
        
        res.status(500).json({ 
            error: "Failed to process PDF: " + (err.message || "Unknown error")
        });
    }
});

// Get import status/info
router.get("/info", ensureAuthenticated, (req, res) => {
    res.json({
        maxFileSize: "100MB",
        supportedFormats: ["PDF"],
        tips: [
            "Export your presentation from PowerPoint, Keynote, or Google Slides as PDF",
            "Each page in the PDF becomes a slide",
            "Higher quality PDFs will produce better slides"
        ]
    });
});

module.exports = router;
