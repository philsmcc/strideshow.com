/**
 * IAM Role Setup Script for StrideShow EC2 Instance
 * 
 * This script creates:
 * 1. IAM Role with EC2 trust policy
 * 2. IAM Policy for S3 access
 * 3. Instance Profile for EC2
 * 
 * After running this script, attach the instance profile to your EC2 instance
 * via AWS Console or CLI.
 */

const {
    IAMClient,
    CreateRoleCommand,
    CreatePolicyCommand,
    AttachRolePolicyCommand,
    CreateInstanceProfileCommand,
    AddRoleToInstanceProfileCommand,
    GetRoleCommand,
    GetInstanceProfileCommand,
} = require('@aws-sdk/client-iam');

const REGION = 'us-west-2';
const ROLE_NAME = 'StrideShowEC2Role';
const POLICY_NAME = 'StrideShowS3Policy';
const INSTANCE_PROFILE_NAME = 'StrideShowEC2Profile';
const BUCKET_NAME = 'strideshow-content';

const iamClient = new IAMClient({ region: REGION });

// Trust policy allowing EC2 to assume this role
const trustPolicy = {
    Version: '2012-10-17',
    Statement: [
        {
            Effect: 'Allow',
            Principal: {
                Service: 'ec2.amazonaws.com'
            },
            Action: 'sts:AssumeRole'
        }
    ]
};

// S3 access policy
const s3Policy = {
    Version: '2012-10-17',
    Statement: [
        {
            Sid: 'AllowS3BucketList',
            Effect: 'Allow',
            Action: ['s3:ListBucket'],
            Resource: [`arn:aws:s3:::${BUCKET_NAME}`]
        },
        {
            Sid: 'AllowS3ObjectAccess',
            Effect: 'Allow',
            Action: [
                's3:PutObject',
                's3:GetObject',
                's3:DeleteObject'
            ],
            Resource: [`arn:aws:s3:::${BUCKET_NAME}/*`]
        }
    ]
};

async function setupIAMRole() {
    console.log('🚀 Setting up IAM Role for StrideShow EC2 Instance');
    console.log('='.repeat(60));

    let policyArn = null;
    let roleCreated = false;

    // Step 1: Create IAM Role
    console.log('\n📋 Step 1: Creating IAM Role...');
    try {
        await iamClient.send(new CreateRoleCommand({
            RoleName: ROLE_NAME,
            AssumeRolePolicyDocument: JSON.stringify(trustPolicy),
            Description: 'IAM role for StrideShow EC2 instance to access S3',
            Tags: [
                { Key: 'Application', Value: 'StrideShow' },
                { Key: 'Purpose', Value: 'EC2 S3 Access' }
            ]
        }));
        console.log(`   ✅ Created IAM Role: ${ROLE_NAME}`);
        roleCreated = true;
    } catch (err) {
        if (err.name === 'EntityAlreadyExistsException') {
            console.log(`   ℹ️  Role already exists: ${ROLE_NAME}`);
            roleCreated = true;
        } else {
            console.error(`   ❌ Failed to create role: ${err.message}`);
            return;
        }
    }

    // Step 2: Create IAM Policy
    console.log('\n📋 Step 2: Creating IAM Policy...');
    try {
        const policyResult = await iamClient.send(new CreatePolicyCommand({
            PolicyName: POLICY_NAME,
            PolicyDocument: JSON.stringify(s3Policy),
            Description: 'Policy for StrideShow S3 bucket access',
            Tags: [
                { Key: 'Application', Value: 'StrideShow' }
            ]
        }));
        policyArn = policyResult.Policy.Arn;
        console.log(`   ✅ Created IAM Policy: ${POLICY_NAME}`);
        console.log(`   Policy ARN: ${policyArn}`);
    } catch (err) {
        if (err.name === 'EntityAlreadyExistsException') {
            // Get the existing policy ARN
            const accountId = await getAccountId();
            policyArn = `arn:aws:iam::${accountId}:policy/${POLICY_NAME}`;
            console.log(`   ℹ️  Policy already exists: ${POLICY_NAME}`);
            console.log(`   Policy ARN: ${policyArn}`);
        } else {
            console.error(`   ❌ Failed to create policy: ${err.message}`);
            return;
        }
    }

    // Step 3: Attach Policy to Role
    console.log('\n📋 Step 3: Attaching Policy to Role...');
    try {
        await iamClient.send(new AttachRolePolicyCommand({
            RoleName: ROLE_NAME,
            PolicyArn: policyArn
        }));
        console.log(`   ✅ Attached policy to role`);
    } catch (err) {
        if (err.message.includes('already attached')) {
            console.log(`   ℹ️  Policy already attached to role`);
        } else {
            console.error(`   ❌ Failed to attach policy: ${err.message}`);
        }
    }

    // Step 4: Create Instance Profile
    console.log('\n📋 Step 4: Creating Instance Profile...');
    try {
        await iamClient.send(new CreateInstanceProfileCommand({
            InstanceProfileName: INSTANCE_PROFILE_NAME,
            Tags: [
                { Key: 'Application', Value: 'StrideShow' }
            ]
        }));
        console.log(`   ✅ Created Instance Profile: ${INSTANCE_PROFILE_NAME}`);
    } catch (err) {
        if (err.name === 'EntityAlreadyExistsException') {
            console.log(`   ℹ️  Instance Profile already exists: ${INSTANCE_PROFILE_NAME}`);
        } else {
            console.error(`   ❌ Failed to create instance profile: ${err.message}`);
        }
    }

    // Step 5: Add Role to Instance Profile
    console.log('\n📋 Step 5: Adding Role to Instance Profile...');
    try {
        await iamClient.send(new AddRoleToInstanceProfileCommand({
            InstanceProfileName: INSTANCE_PROFILE_NAME,
            RoleName: ROLE_NAME
        }));
        console.log(`   ✅ Added role to instance profile`);
    } catch (err) {
        if (err.name === 'LimitExceededException' || err.message.includes('already exists')) {
            console.log(`   ℹ️  Role already added to instance profile`);
        } else {
            console.error(`   ❌ Failed to add role to profile: ${err.message}`);
        }
    }

    // Summary
    console.log('\n' + '='.repeat(60));
    console.log('🎉 IAM Role Setup Complete!');
    console.log('='.repeat(60));
    console.log('\n📌 Next Steps:');
    console.log('   1. Go to AWS EC2 Console');
    console.log('   2. Select your StrideShow EC2 instance');
    console.log('   3. Click Actions > Security > Modify IAM Role');
    console.log(`   4. Select "${INSTANCE_PROFILE_NAME}" from the dropdown`);
    console.log('   5. Click "Update IAM role"');
    console.log('\n   Or use AWS CLI:');
    console.log(`   aws ec2 associate-iam-instance-profile \\`);
    console.log(`       --instance-id <YOUR_INSTANCE_ID> \\`);
    console.log(`       --iam-instance-profile Name=${INSTANCE_PROFILE_NAME}`);
    console.log('\n📌 After attaching the role:');
    console.log('   1. Delete ~/.aws/credentials file (rm ~/.aws/credentials)');
    console.log('   2. The application will automatically use the IAM role');
    console.log('\nResources Created:');
    console.log(`   • IAM Role: ${ROLE_NAME}`);
    console.log(`   • IAM Policy: ${POLICY_NAME}`);
    console.log(`   • Instance Profile: ${INSTANCE_PROFILE_NAME}`);
}

async function getAccountId() {
    const { STSClient, GetCallerIdentityCommand } = require('@aws-sdk/client-sts');
    const stsClient = new STSClient({ region: REGION });
    const result = await stsClient.send(new GetCallerIdentityCommand({}));
    return result.Account;
}

// Run setup
setupIAMRole().catch(err => {
    console.error('Fatal error:', err);
    process.exit(1);
});
