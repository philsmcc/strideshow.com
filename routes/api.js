const express = require('express');
const router = express.Router();
const pool = require('../config/database');
const { ensureAuthenticated } = require('../middleware/auth');
const QRCode = require('qrcode');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const s3Storage = require('../s3-storage');

// Allowed media types
const ALLOWED_IMAGE_TYPES = /jpeg|jpg|png|gif|webp/;
const ALLOWED_VIDEO_TYPES = /mp4|webm|mov|avi|mkv|m4v/;
const ALLOWED_IMAGE_MIMES = /image\/(jpeg|jpg|png|gif|webp)/;
const ALLOWED_VIDEO_MIMES = /video\/(mp4|webm|quicktime|x-msvideo|x-matroska|x-m4v)/;

// Helper to determine media type from file
function getMediaType(file) {
    const ext = path.extname(file.originalname).toLowerCase().slice(1);
    if (ALLOWED_VIDEO_TYPES.test(ext) || file.mimetype.startsWith('video/')) {
        return 'video';
    }
    return 'image';
}

// Configure multer for memory storage (for S3 uploads)
// Increased limit to 500MB to support video files
const upload = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: 500 * 1024 * 1024 }, // 500MB limit
    fileFilter: function (req, file, cb) {
        const ext = path.extname(file.originalname).toLowerCase();
        const isImage = ALLOWED_IMAGE_TYPES.test(ext.slice(1)) || ALLOWED_IMAGE_MIMES.test(file.mimetype);
        const isVideo = ALLOWED_VIDEO_TYPES.test(ext.slice(1)) || ALLOWED_VIDEO_MIMES.test(file.mimetype);
        
        if (isImage || isVideo) {
            return cb(null, true);
        }
        cb(new Error('Only image files (JPEG, PNG, GIF, WebP) and video files (MP4, WebM, MOV, AVI, MKV) are allowed!'));
    }
});

// Get all slideshows for the logged-in user
router.get('/slideshows', ensureAuthenticated, async (req, res) => {
    try {
        const result = await pool.query(
            `SELECT s.*, COUNT(sl.id) as slide_count 
             FROM slideshows s 
             LEFT JOIN slides sl ON s.id = sl.slideshow_id 
             WHERE s.user_id = $1 
             GROUP BY s.id 
             ORDER BY s.updated_at DESC`,
            [req.user.id]
        );
        res.json(result.rows);
    } catch (error) {
        console.error('Error fetching slideshows:', error);
        res.status(500).json({ error: 'Failed to fetch slideshows' });
    }
});

// Create a new slideshow
router.post('/slideshows', ensureAuthenticated, async (req, res) => {
    const { title } = req.body;
    
    if (!title) {
        return res.status(400).json({ error: 'Title is required' });
    }
    
    try {
        const result = await pool.query(
            'INSERT INTO slideshows (user_id, title) VALUES ($1, $2) RETURNING *',
            [req.user.id, title]
        );
        res.json(result.rows[0]);
    } catch (error) {
        console.error('Error creating slideshow:', error);
        res.status(500).json({ error: 'Failed to create slideshow' });
    }
});

// Update slideshow title
router.put('/slideshows/:id', ensureAuthenticated, async (req, res) => {
    const { title } = req.body;
    
    if (!title) {
        return res.status(400).json({ error: 'Title is required' });
    }
    
    try {
        const result = await pool.query(
            'UPDATE slideshows SET title = $1, updated_at = NOW() WHERE id = $2 AND user_id = $3 RETURNING *',
            [title, req.params.id, req.user.id]
        );
        
        if (result.rows.length === 0) {
            return res.status(404).json({ error: 'Slideshow not found' });
        }
        
        res.json(result.rows[0]);
    } catch (error) {
        console.error('Error updating slideshow:', error);
        res.status(500).json({ error: 'Failed to update slideshow' });
    }
});

// Delete a slideshow
router.delete('/slideshows/:id', ensureAuthenticated, async (req, res) => {
    try {
        // Get slides to delete their files
        const slidesResult = await pool.query(
            `SELECT s.content_url FROM slides s 
             JOIN slideshows ss ON s.slideshow_id = ss.id 
             WHERE ss.id = $1 AND ss.user_id = $2`,
            [req.params.id, req.user.id]
        );
        
        // Delete slide files from S3 or local storage
        for (const slide of slidesResult.rows) {
            if (slide.content_url) {
                if (s3Storage.isS3Url(slide.content_url)) {
                    // Delete from S3
                    await s3Storage.deleteFile(slide.content_url);
                } else if (slide.content_url.startsWith('/uploads/')) {
                    // Legacy: Delete from local storage
                    const filePath = path.join(__dirname, '../public', slide.content_url);
                    if (fs.existsSync(filePath)) {
                        fs.unlinkSync(filePath);
                    }
                }
            }
        }
        
        // Delete slideshow (cascade will delete slides)
        const result = await pool.query(
            'DELETE FROM slideshows WHERE id = $1 AND user_id = $2 RETURNING id',
            [req.params.id, req.user.id]
        );
        
        if (result.rows.length === 0) {
            return res.status(404).json({ error: 'Slideshow not found' });
        }
        
        res.json({ success: true });
    } catch (error) {
        console.error('Error deleting slideshow:', error);
        res.status(500).json({ error: 'Failed to delete slideshow' });
    }
});

// Get slides for a slideshow
router.get('/slideshows/:id/slides', ensureAuthenticated, async (req, res) => {
    try {
        // First verify ownership
        const showResult = await pool.query(
            'SELECT * FROM slideshows WHERE id = $1 AND user_id = $2',
            [req.params.id, req.user.id]
        );
        
        if (showResult.rows.length === 0) {
            return res.status(404).json({ error: 'Slideshow not found' });
        }
        
        const result = await pool.query(
            'SELECT id, slideshow_id, slide_order, content_url as image_url, thumbnail_url, notes, title, media_type FROM slides WHERE slideshow_id = $1 ORDER BY slide_order ASC',
            [req.params.id]
        );
        res.json(result.rows);
    } catch (error) {
        console.error('Error fetching slides:', error);
        res.status(500).json({ error: 'Failed to fetch slides' });
    }
});

// Add a slide with file upload (supports images and videos)
router.post('/slideshows/:id/slides', ensureAuthenticated, upload.single('image'), async (req, res) => {
    try {
        // Verify ownership
        const showResult = await pool.query(
            'SELECT * FROM slideshows WHERE id = $1 AND user_id = $2',
            [req.params.id, req.user.id]
        );
        
        if (showResult.rows.length === 0) {
            return res.status(404).json({ error: 'Slideshow not found' });
        }
        
        // Get the media URL - either from uploaded file or from body
        let mediaUrl = null;
        let mediaType = 'image'; // default
        
        if (req.file) {
            // Determine media type
            mediaType = getMediaType(req.file);
            
            // Upload to S3 with appropriate folder
            const folder = mediaType === 'video' ? 'videos' : 'slides';
            const s3Key = s3Storage.generateS3Key(req.user.id, req.file.originalname, folder);
            const contentType = s3Storage.getContentType(req.file.originalname);
            mediaUrl = await s3Storage.uploadFile(req.file.buffer, s3Key, contentType);
        } else if (req.body.image_url) {
            mediaUrl = req.body.image_url;
            // Try to detect media type from URL
            if (/\.(mp4|webm|mov|avi|mkv|m4v)$/i.test(req.body.image_url)) {
                mediaType = 'video';
            }
        }
        
        const notes = req.body.notes || null;
        const title = req.body.title || null;
        
        // Get next position
        const posResult = await pool.query(
            'SELECT COALESCE(MAX(slide_order), 0) + 1 as next_pos FROM slides WHERE slideshow_id = $1',
            [req.params.id]
        );
        const nextPos = posResult.rows[0].next_pos;
        
        const result = await pool.query(
            'INSERT INTO slides (slideshow_id, slide_order, content_url, notes, title, media_type) VALUES ($1, $2, $3, $4, $5, $6) RETURNING id, slideshow_id, slide_order, content_url as image_url, notes, title, media_type',
            [req.params.id, nextPos, mediaUrl, notes, title, mediaType]
        );
        
        // Update slideshow timestamp
        await pool.query('UPDATE slideshows SET updated_at = NOW() WHERE id = $1', [req.params.id]);
        
        res.json(result.rows[0]);
    } catch (error) {
        console.error('Error adding slide:', error);
        res.status(500).json({ error: 'Failed to add slide' });
    }
});

// Update a slide (title and notes)
router.put('/slides/:id', ensureAuthenticated, async (req, res) => {
    const { title, notes } = req.body;
    
    try {
        // Verify ownership through slideshow
        const slideResult = await pool.query(
            `SELECT s.*, ss.user_id FROM slides s 
             JOIN slideshows ss ON s.slideshow_id = ss.id 
             WHERE s.id = $1 AND ss.user_id = $2`,
            [req.params.id, req.user.id]
        );
        
        if (slideResult.rows.length === 0) {
            return res.status(404).json({ error: 'Slide not found' });
        }
        
        const result = await pool.query(
            'UPDATE slides SET title = $1, notes = $2 WHERE id = $3 RETURNING id, slideshow_id, slide_order, content_url as image_url, thumbnail_url, notes, title, media_type',
            [title || null, notes || null, req.params.id]
        );
        
        // Update slideshow timestamp
        await pool.query('UPDATE slideshows SET updated_at = NOW() WHERE id = $1', [slideResult.rows[0].slideshow_id]);
        
        res.json(result.rows[0]);
    } catch (error) {
        console.error('Error updating slide:', error);
        res.status(500).json({ error: 'Failed to update slide' });
    }
});

// Reorder slides
router.put('/slideshows/:id/reorder', ensureAuthenticated, async (req, res) => {
    const { slideOrder } = req.body; // Array of slide IDs in new order
    
    if (!Array.isArray(slideOrder)) {
        return res.status(400).json({ error: 'slideOrder must be an array of slide IDs' });
    }
    
    try {
        // Verify ownership
        const showResult = await pool.query(
            'SELECT * FROM slideshows WHERE id = $1 AND user_id = $2',
            [req.params.id, req.user.id]
        );
        
        if (showResult.rows.length === 0) {
            return res.status(404).json({ error: 'Slideshow not found' });
        }
        
        // Update each slide's order
        for (let i = 0; i < slideOrder.length; i++) {
            await pool.query(
                'UPDATE slides SET slide_order = $1 WHERE id = $2 AND slideshow_id = $3',
                [i + 1, slideOrder[i], req.params.id]
            );
        }
        
        // Update slideshow timestamp
        await pool.query('UPDATE slideshows SET updated_at = NOW() WHERE id = $1', [req.params.id]);
        
        res.json({ success: true });
    } catch (error) {
        console.error('Error reordering slides:', error);
        res.status(500).json({ error: 'Failed to reorder slides' });
    }
});

// Delete a slide
router.delete('/slides/:id', ensureAuthenticated, async (req, res) => {
    try {
        // Get the slide first to delete the file
        const slideResult = await pool.query(
            `SELECT s.* FROM slides s 
             JOIN slideshows ss ON s.slideshow_id = ss.id 
             WHERE s.id = $1 AND ss.user_id = $2`,
            [req.params.id, req.user.id]
        );
        
        if (slideResult.rows.length === 0) {
            return res.status(404).json({ error: 'Slide not found' });
        }
        
        const slide = slideResult.rows[0];
        
        // Delete the file from S3 or local storage
        if (slide.content_url) {
            if (s3Storage.isS3Url(slide.content_url)) {
                // Delete from S3
                await s3Storage.deleteFile(slide.content_url);
            } else if (slide.content_url.startsWith('/uploads/')) {
                // Legacy: Delete from local storage
                const filePath = path.join(__dirname, '../public', slide.content_url);
                if (fs.existsSync(filePath)) {
                    fs.unlinkSync(filePath);
                }
            }
        }
        
        // Delete from database
        await pool.query('DELETE FROM slides WHERE id = $1', [req.params.id]);
        
        // Update slideshow timestamp
        await pool.query('UPDATE slideshows SET updated_at = NOW() WHERE id = $1', [slide.slideshow_id]);
        
        res.json({ success: true });
    } catch (error) {
        console.error('Error deleting slide:', error);
        res.status(500).json({ error: 'Failed to delete slide' });
    }
});

// Validate screen code
router.get('/screens/validate/:code', async (req, res) => {
    const code = req.params.code.toUpperCase();
    
    try {
        const result = await pool.query(
            'SELECT * FROM display_screens WHERE screen_code = $1 AND is_active = true AND expires_at > NOW()',
            [code]
        );
        
        if (result.rows.length === 0) {
            return res.json({ valid: false, error: 'Screen not found or expired' });
        }
        
        res.json({ valid: true, screenCode: code });
    } catch (error) {
        console.error('Error validating screen:', error);
        res.status(500).json({ valid: false, error: 'Validation failed' });
    }
});

// Generate QR code for a screen
router.get('/screens/:code/qr', async (req, res) => {
    const code = req.params.code.toUpperCase();
    const baseUrl = process.env.BASE_URL || 'https://www.strideshow.com';
    const connectUrl = `${baseUrl}/connect/${code}`;
    
    try {
        const qrCode = await QRCode.toDataURL(connectUrl, {
            width: 300,
            margin: 2
        });
        res.json({ qrCode, connectUrl });
    } catch (error) {
        console.error('Error generating QR code:', error);
        res.status(500).json({ error: 'Failed to generate QR code' });
    }
});

module.exports = router;
