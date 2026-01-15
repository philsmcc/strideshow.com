const passport = require("passport");
const GoogleStrategy = require("passport-google-oauth20").Strategy;
const pool = require("./database");

passport.serializeUser((user, done) => {
    done(null, user.id);
});

passport.deserializeUser(async (id, done) => {
    try {
        const result = await pool.query("SELECT * FROM users WHERE id = $1", [id]);
        done(null, result.rows[0]);
    } catch (err) {
        done(err, null);
    }
});

passport.use(
    new GoogleStrategy(
        {
            clientID: process.env.GOOGLE_CLIENT_ID,
            clientSecret: process.env.GOOGLE_CLIENT_SECRET,
            callbackURL: `${process.env.BASE_URL}/auth/google/callback`,
            scope: [
                "profile", 
                "email", 
                "https://www.googleapis.com/auth/presentations.readonly",
                "https://www.googleapis.com/auth/drive.readonly"
            ],
            accessType: "offline",
            prompt: "consent"
        },
        async (accessToken, refreshToken, profile, done) => {
            try {
                console.log("Google OAuth callback - profile:", profile.id, profile.displayName);
                console.log("Access token received:", accessToken ? "yes" : "no");
                console.log("Refresh token received:", refreshToken ? "yes" : "no");
                
                const email = profile.emails[0].value;
                const tokenExpiry = new Date(Date.now() + 3600 * 1000);
                
                // First, check if user exists by Google ID
                let result = await pool.query(
                    "SELECT * FROM users WHERE google_id = $1",
                    [profile.id]
                );

                if (result.rows.length > 0) {
                    // User exists with this Google ID - update tokens
                    console.log("Found existing user by Google ID:", result.rows[0].id);
                    result = await pool.query(
                        `UPDATE users SET 
                            name = $1, 
                            avatar_url = $2, 
                            google_access_token = $3,
                            google_refresh_token = COALESCE($4, google_refresh_token),
                            google_token_expiry = $5,
                            updated_at = NOW() 
                        WHERE google_id = $6 RETURNING *`,
                        [
                            profile.displayName, 
                            profile.photos?.[0]?.value, 
                            accessToken,
                            refreshToken,
                            tokenExpiry,
                            profile.id
                        ]
                    );
                    return done(null, result.rows[0]);
                }

                // Check if user exists by email (existing account without Google)
                result = await pool.query(
                    "SELECT * FROM users WHERE email = $1",
                    [email]
                );

                if (result.rows.length > 0) {
                    // User exists with this email - link Google account to it
                    console.log("Found existing user by email, linking Google account:", result.rows[0].id);
                    result = await pool.query(
                        `UPDATE users SET 
                            google_id = $1,
                            name = COALESCE(name, $2), 
                            avatar_url = COALESCE(avatar_url, $3), 
                            google_access_token = $4,
                            google_refresh_token = $5,
                            google_token_expiry = $6,
                            updated_at = NOW() 
                        WHERE email = $7 RETURNING *`,
                        [
                            profile.id,
                            profile.displayName, 
                            profile.photos?.[0]?.value, 
                            accessToken,
                            refreshToken,
                            tokenExpiry,
                            email
                        ]
                    );
                    console.log("Linked Google account to existing user:", result.rows[0].id);
                    return done(null, result.rows[0]);
                }

                // Create new user
                console.log("Creating new user for:", email);
                result = await pool.query(
                    `INSERT INTO users (email, google_id, name, avatar_url, google_access_token, google_refresh_token, google_token_expiry) 
                     VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING *`,
                    [
                        email,
                        profile.id,
                        profile.displayName,
                        profile.photos?.[0]?.value,
                        accessToken,
                        refreshToken,
                        tokenExpiry
                    ]
                );
                console.log("Created new user:", result.rows[0].id);
                done(null, result.rows[0]);
            } catch (err) {
                console.error("Google OAuth error:", err);
                done(err, null);
            }
        }
    )
);

module.exports = passport;
