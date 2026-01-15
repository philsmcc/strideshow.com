const express = require('express');
const router = express.Router();
const { ensureAuthenticated } = require('../middleware/auth');
const pool = require('../config/database');

// Home page
router.get('/', (req, res) => {
    res.render('index', { user: req.user });
});

// Login page
router.get('/login', (req, res) => {
    if (req.isAuthenticated()) {
        return res.redirect('/dashboard');
    }
    res.render('login', { user: null, error: null });
});

// Register page
router.get('/register', (req, res) => {
    if (req.isAuthenticated()) {
        return res.redirect('/dashboard');
    }
    res.render('register', { user: null, error: null });
});

// Privacy Policy
router.get('/privacy', (req, res) => {
    res.render('privacy', { user: req.user });
});

// Terms of Service
router.get('/terms', (req, res) => {
    res.render('terms', { user: req.user });
});

// Display screen - NO LOGIN REQUIRED
// This is where you open on a TV/projector
router.get('/go', (req, res) => {
    const { v4: uuidv4 } = require('uuid');
    const screenId = uuidv4();
    res.render('display', { 
        screenId: screenId,
        baseUrl: process.env.BASE_URL || 'https://www.strideshow.com'
    });
});

// Connect page - for entering code (requires login)
router.get('/connect', ensureAuthenticated, (req, res) => {
    res.render('connect', { user: req.user });
});

// Presenter controls - connect to specific screen (requires login)
router.get('/connect/:code', ensureAuthenticated, async (req, res) => {
    const screenCode = req.params.code.toUpperCase();
    
    try {
        // Check if screen exists and is active
        const result = await pool.query(
            'SELECT * FROM display_screens WHERE screen_code = $1 AND is_active = true AND expires_at > NOW()',
            [screenCode]
        );
        
        if (result.rows.length === 0) {
            return res.render('error', { 
                user: req.user,
                error: 'Screen not found or expired',
                message: 'The display screen with this code is no longer available. Please check the code and try again.'
            });
        }
        
        res.render('presenter', { 
            user: req.user, 
            screenCode: screenCode,
            screen: result.rows[0]
        });
    } catch (error) {
        console.error('Error connecting to screen:', error);
        res.render('error', { 
            user: req.user,
            error: 'Connection Error',
            message: 'Unable to connect to the display screen. Please try again.'
        });
    }
});

module.exports = router;
