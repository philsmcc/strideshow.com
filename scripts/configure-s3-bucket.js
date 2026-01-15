/**
 * S3 Bucket Configuration and Optimization Script
 * 
 * This script configures the strideshow-content bucket with:
 * - CORS for web access
 * - Public read access for slide images
 * - Lifecycle rules to manage storage costs
 * - Proper bucket policy
 */

const { 
    S3Client, 
    PutBucketCorsCommand,
    PutBucketLifecycleConfigurationCommand,
    PutBucketPolicyCommand,
    PutPublicAccessBlockCommand,
    GetBucketLocationCommand,
    HeadBucketCommand
} = require('@aws-sdk/client-s3');

const BUCKET_NAME = 'strideshow-content';
const REGION = 'us-west-2';

const s3Client = new S3Client({ region: REGION });

async function configureBucket() {
    console.log('🚀 Starting S3 bucket configuration for:', BUCKET_NAME);
    console.log('=' .repeat(60));

    // 1. Verify bucket exists and we have access
    console.log('\n📋 Step 1: Verifying bucket access...');
    try {
        await s3Client.send(new HeadBucketCommand({ Bucket: BUCKET_NAME }));
        console.log('   ✅ Bucket exists and is accessible');
    } catch (err) {
        console.error('   ❌ Cannot access bucket:', err.message);
        process.exit(1);
    }

    // 2. Configure CORS for web access
    console.log('\n📋 Step 2: Configuring CORS for web access...');
    try {
        const corsConfig = {
            Bucket: BUCKET_NAME,
            CORSConfiguration: {
                CORSRules: [
                    {
                        AllowedHeaders: ['*'],
                        AllowedMethods: ['GET', 'PUT', 'POST', 'HEAD'],
                        AllowedOrigins: [
                            'https://www.strideshow.com',
                            'https://strideshow.com',
                            'http://localhost:3000',  // For development
                            'http://localhost:5000',
                        ],
                        ExposeHeaders: ['ETag', 'Content-Length', 'Content-Type'],
                        MaxAgeSeconds: 3600
                    }
                ]
            }
        };
        await s3Client.send(new PutBucketCorsCommand(corsConfig));
        console.log('   ✅ CORS configured successfully');
    } catch (err) {
        console.error('   ❌ CORS configuration failed:', err.message);
    }

    // 3. Configure public access settings (allow public read for images)
    console.log('\n📋 Step 3: Configuring public access settings...');
    try {
        await s3Client.send(new PutPublicAccessBlockCommand({
            Bucket: BUCKET_NAME,
            PublicAccessBlockConfiguration: {
                BlockPublicAcls: false,
                IgnorePublicAcls: false,
                BlockPublicPolicy: false,
                RestrictPublicBuckets: false
            }
        }));
        console.log('   ✅ Public access block settings configured');
    } catch (err) {
        console.error('   ❌ Public access settings failed:', err.message);
    }

    // 4. Set bucket policy for public read access to slides
    console.log('\n📋 Step 4: Setting bucket policy for public read access...');
    try {
        const bucketPolicy = {
            Version: '2012-10-17',
            Statement: [
                {
                    Sid: 'PublicReadForSlides',
                    Effect: 'Allow',
                    Principal: '*',
                    Action: 's3:GetObject',
                    Resource: `arn:aws:s3:::${BUCKET_NAME}/users/*/slides/*`
                }
            ]
        };

        await s3Client.send(new PutBucketPolicyCommand({
            Bucket: BUCKET_NAME,
            Policy: JSON.stringify(bucketPolicy)
        }));
        console.log('   ✅ Bucket policy set - slides are publicly readable');
    } catch (err) {
        console.error('   ❌ Bucket policy failed:', err.message);
    }

    // 5. Configure lifecycle rules for cost optimization
    console.log('\n📋 Step 5: Configuring lifecycle rules for cost optimization...');
    try {
        const lifecycleConfig = {
            Bucket: BUCKET_NAME,
            LifecycleConfiguration: {
                Rules: [
                    {
                        ID: 'TransitionToInfrequentAccess',
                        Status: 'Enabled',
                        Filter: {
                            Prefix: 'users/'
                        },
                        Transitions: [
                            {
                                Days: 90,
                                StorageClass: 'STANDARD_IA'  // Infrequent Access after 90 days
                            }
                        ]
                    },
                    {
                        ID: 'CleanupIncompleteMultipartUploads',
                        Status: 'Enabled',
                        Filter: {
                            Prefix: ''
                        },
                        AbortIncompleteMultipartUpload: {
                            DaysAfterInitiation: 7  // Clean up incomplete uploads after 7 days
                        }
                    },
                    {
                        ID: 'DeleteOldVersions',
                        Status: 'Enabled',
                        Filter: {
                            Prefix: ''
                        },
                        NoncurrentVersionExpiration: {
                            NoncurrentDays: 30  // Delete old versions after 30 days
                        }
                    }
                ]
            }
        };

        await s3Client.send(new PutBucketLifecycleConfigurationCommand(lifecycleConfig));
        console.log('   ✅ Lifecycle rules configured:');
        console.log('      - Move to Infrequent Access after 90 days');
        console.log('      - Clean up incomplete uploads after 7 days');
        console.log('      - Delete old versions after 30 days');
    } catch (err) {
        console.error('   ❌ Lifecycle configuration failed:', err.message);
    }

    // Summary
    console.log('\n' + '=' .repeat(60));
    console.log('🎉 S3 Bucket Configuration Complete!');
    console.log('=' .repeat(60));
    console.log('\nBucket URL: https://' + BUCKET_NAME + '.s3.' + REGION + '.amazonaws.com/');
    console.log('\nSlide URLs will be accessible at:');
    console.log('https://' + BUCKET_NAME + '.s3.' + REGION + '.amazonaws.com/users/{userId}/slides/{filename}');
    console.log('\nConfiguration Summary:');
    console.log('  ✓ CORS enabled for strideshow.com');
    console.log('  ✓ Public read access for slide images');
    console.log('  ✓ Cost optimization lifecycle rules');
    console.log('  ✓ Automatic cleanup of incomplete uploads');
}

// Run configuration
configureBucket().catch(err => {
    console.error('Fatal error:', err);
    process.exit(1);
});
