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
            scope: ["profile", "email"]
        },
        async (accessToken, refreshToken, profile, done) => {
            try {
                console.log("Google OAuth callback - profile:", profile.id, profile.displayName);
                
                const email = profile.emails[0].value;
                
                // First, check if user exists by Google ID
                let result = await pool.query(
                    "SELECT * FROM users WHERE google_id = $1",
                    [profile.id]
                );

                if (result.rows.length > 0) {
                    // User exists with this Google ID - update info
                    console.log("Found existing user by Google ID:", result.rows[0].id);
                    result = await pool.query(
                        `UPDATE users SET 
                            name = $1,
                            avatar_url = $2,
                            updated_at = NOW()
                         WHERE google_id = $3 RETURNING *`,
                        [
                            profile.displayName, 
                            profile.photos?.[0]?.value, 
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
                            updated_at = NOW()
                         WHERE email = $4 RETURNING *`,
                        [
                            profile.id,
                            profile.displayName, 
                            profile.photos?.[0]?.value, 
                            email
                        ]
                    );
                    console.log("Linked Google account to existing user:", result.rows[0].id);
                    return done(null, result.rows[0]);
                }

                // Create new user
                console.log("Creating new user for:", email);
                result = await pool.query(
                    `INSERT INTO users (email, google_id, name, avatar_url)
                     VALUES ($1, $2, $3, $4) RETURNING *`,
                    [
                        email,
                        profile.id,
                        profile.displayName,
                        profile.photos?.[0]?.value
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
