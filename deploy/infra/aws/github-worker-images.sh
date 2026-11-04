#!/usr/bin/env bash
# Lets the worker-image workflow push to public ECR without stored keys: GitHub's OIDC provider in this AWS account and
# a role only workflow runs on this repository's main branch can assume, allowed to push to the one worker repository.
# Idempotent; prints the role ARN to set as the repository variable AWS_WORKER_IMAGE_ROLE.
# Usage: deploy/infra/aws/github-worker-images.sh   (with credentials for the account that owns the ECR repository)
set -euo pipefail
REPO_SLUG=${REPO_SLUG:-Eignex/eignex-lab}
ROLE=${ROLE:-eignex-lab-worker-image-publisher}
ECR_REPOSITORY=${ECR_REPOSITORY:-eignex-lab-worker}
ISSUER=token.actions.githubusercontent.com

account=$(aws sts get-caller-identity --query Account --output text)
provider="arn:aws:iam::${account}:oidc-provider/${ISSUER}"
if ! aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$provider" > /dev/null 2>&1; then
  # AWS validates GitHub's certificate chain itself; the thumbprint is required by the API but not used for GitHub.
  aws iam create-open-id-connect-provider --url "https://${ISSUER}" --client-id-list sts.amazonaws.com \
    --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 > /dev/null
  echo "created OIDC provider $provider"
fi

trust=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Federated": "${provider}"},
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "${ISSUER}:aud": "sts.amazonaws.com",
        "${ISSUER}:sub": "repo:${REPO_SLUG}:ref:refs/heads/main"
      }
    }
  }]
}
EOF
)
permissions=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Action": ["ecr-public:GetAuthorizationToken", "sts:GetServiceBearerToken"], "Resource": "*"},
    {"Effect": "Allow",
     "Action": ["ecr-public:BatchCheckLayerAvailability", "ecr-public:InitiateLayerUpload", "ecr-public:UploadLayerPart",
                "ecr-public:CompleteLayerUpload", "ecr-public:PutImage", "ecr-public:DescribeImages"],
     "Resource": "arn:aws:ecr-public::${account}:repository/${ECR_REPOSITORY}"}
  ]
}
EOF
)
if aws iam get-role --role-name "$ROLE" > /dev/null 2>&1; then
  aws iam update-assume-role-policy --role-name "$ROLE" --policy-document "$trust"
else
  aws iam create-role --role-name "$ROLE" --assume-role-policy-document "$trust" \
    --description "GitHub ${REPO_SLUG} main: push worker images to public ECR ${ECR_REPOSITORY}" --max-session-duration 3600 > /dev/null
  echo "created role $ROLE"
fi
aws iam put-role-policy --role-name "$ROLE" --policy-name push-worker-images --policy-document "$permissions"
aws iam get-role --role-name "$ROLE" --query Role.Arn --output text
