/**
 * S3 Storage Utility Module
 * Handles all S3 operations for user content storage
 * 
 * S3 Bucket: strideshow-content
 * ARN: arn:aws:s3:::strideshow-content
 */

const { S3Client, PutObjectCommand, DeleteObjectCommand, GetObjectCommand } = require('@aws-sdk/client-s3');
const { getSignedUrl } = require('@aws-sdk/s3-request-presigner');
const path = require('path');
const crypto = require('crypto');

// S3 Configuration
const BUCKET_NAME = 'strideshow-content';
const REGION = process.env.AWS_REGION || 'us-east-1';

// Initialize S3 Client
// Uses default AWS credential chain (environment variables, IAM role, etc.)
const s3Client = new S3Client({
    region: REGION,
    // Credentials will be automatically loaded from:
    // 1. Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
    // 2. IAM instance role (if running on EC2)
    // 3. ~/.aws/credentials file
});

/**
 * Generate a unique key for S3 storage
 * Format: users/{userId}/slides/{timestamp}-{random}.{ext}
 */
function generateS3Key(userId, filename, folder = 'slides') {
    const ext = path.extname(filename).toLowerCase() || '.png';
    const timestamp = Date.now();
    const random = crypto.randomBytes(8).toString('hex');
    return `users/${userId}/${folder}/${timestamp}-${random}${ext}`;
}

/**
 * Upload a file buffer to S3
 * @param {Buffer} fileBuffer - The file data
 * @param {string} key - The S3 object key
 * @param {string} contentType - MIME type of the file
 * @returns {Promise<string>} - The public URL or S3 URI
 */
async function uploadFile(fileBuffer, key, contentType) {
    const command = new PutObjectCommand({
        Bucket: BUCKET_NAME,
        Key: key,
        Body: fileBuffer,
        ContentType: contentType,
        // Cache control for browser caching
        CacheControl: 'max-age=31536000', // 1 year
    });

    await s3Client.send(command);
    
    // Return the S3 URL
    return `https://${BUCKET_NAME}.s3.${REGION}.amazonaws.com/${key}`;
}

/**
 * Upload a file from disk to S3
 * @param {string} filePath - Local file path
 * @param {string} key - The S3 object key
 * @param {string} contentType - MIME type of the file
 * @returns {Promise<string>} - The public URL
 */
async function uploadFileFromPath(filePath, key, contentType) {
    const fs = require('fs').promises;
    const fileBuffer = await fs.readFile(filePath);
    return uploadFile(fileBuffer, key, contentType);
}

/**
 * Delete a file from S3
 * @param {string} key - The S3 object key (or full URL)
 * @returns {Promise<void>}
 */
async function deleteFile(key) {
    // Extract key from URL if full URL is provided
    const s3Key = extractKeyFromUrl(key);
    
    if (!s3Key) {
        console.warn('Cannot delete: Invalid S3 key or URL:', key);
        return;
    }

    const command = new DeleteObjectCommand({
        Bucket: BUCKET_NAME,
        Key: s3Key,
    });

    try {
        await s3Client.send(command);
    } catch (err) {
        console.error('Error deleting from S3:', err);
        // Don't throw - deletion failures shouldn't break the app
    }
}

/**
 * Delete multiple files from S3
 * @param {string[]} keys - Array of S3 keys or URLs
 * @returns {Promise<void>}
 */
async function deleteFiles(keys) {
    await Promise.all(keys.map(key => deleteFile(key)));
}

/**
 * Generate a presigned URL for temporary access
 * @param {string} key - The S3 object key
 * @param {number} expiresIn - URL validity in seconds (default 1 hour)
 * @returns {Promise<string>} - Presigned URL
 */
async function getPresignedUrl(key, expiresIn = 3600) {
    const command = new GetObjectCommand({
        Bucket: BUCKET_NAME,
        Key: key,
    });

    return getSignedUrl(s3Client, command, { expiresIn });
}

/**
 * Extract S3 key from a full S3 URL
 * @param {string} url - Full S3 URL or key
 * @returns {string|null} - The S3 key or null
 */
function extractKeyFromUrl(url) {
    if (!url) return null;
    
    // If it's already just a key (doesn't start with http)
    if (!url.startsWith('http')) {
        return url;
    }
    
    // Handle S3 URL formats:
    // https://bucket-name.s3.region.amazonaws.com/key
    // https://s3.region.amazonaws.com/bucket-name/key
    try {
        const urlObj = new URL(url);
        
        // Format: bucket-name.s3.region.amazonaws.com/key
        if (urlObj.hostname.includes('.s3.') && urlObj.hostname.includes('.amazonaws.com')) {
            return urlObj.pathname.substring(1); // Remove leading /
        }
        
        // Format: s3.region.amazonaws.com/bucket-name/key
        if (urlObj.hostname.startsWith('s3.') && urlObj.hostname.includes('.amazonaws.com')) {
            const pathParts = urlObj.pathname.split('/');
            if (pathParts.length > 2) {
                return pathParts.slice(2).join('/'); // Remove empty first part and bucket name
            }
        }
    } catch (err) {
        console.error('Error parsing S3 URL:', err);
    }
    
    return null;
}

/**
 * Check if a URL is an S3 URL from our bucket
 * @param {string} url - URL to check
 * @returns {boolean}
 */
function isS3Url(url) {
    if (!url) return false;
    return url.includes(BUCKET_NAME) && url.includes('amazonaws.com');
}

/**
 * Check if a URL is a local upload (legacy)
 * @param {string} url - URL to check
 * @returns {boolean}
 */
function isLocalUpload(url) {
    if (!url) return false;
    return url.startsWith('/uploads/');
}

/**
 * Get content type from file extension
 * @param {string} filename - Filename with extension
 * @returns {string} - MIME type
 */
function getContentType(filename) {
    const ext = path.extname(filename).toLowerCase();
    const mimeTypes = {
        '.png': 'image/png',
        '.jpg': 'image/jpeg',
        '.jpeg': 'image/jpeg',
        '.gif': 'image/gif',
        '.webp': 'image/webp',
        '.pdf': 'application/pdf',
        '.svg': 'image/svg+xml',
    };
    return mimeTypes[ext] || 'application/octet-stream';
}

module.exports = {
    s3Client,
    BUCKET_NAME,
    REGION,
    generateS3Key,
    uploadFile,
    uploadFileFromPath,
    deleteFile,
    deleteFiles,
    getPresignedUrl,
    extractKeyFromUrl,
    isS3Url,
    isLocalUpload,
    getContentType,
};
