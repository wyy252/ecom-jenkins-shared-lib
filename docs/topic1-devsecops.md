# Topic 1 - DevSecOps Principles and Practices

## Problem Statement

In the baseline pipeline, security scanning existed, but it was not clearly used as a release decision point. This created a risk that vulnerable code or images could still move into higher environments.

## Architecture and Approach

I integrated Trivy-based scanning into the Jenkins shared library so that all service pipelines can reuse the same DevSecOps logic.

The pipeline now performs:
- filesystem scanning after checkout
- image scanning after container build
- environment-aware security gate behavior

## Acceptance Criteria

- Filesystem scan reports are generated during non-PR builds
- Image scan reports are generated after container build
- Dev builds continue in report-only mode
- Staging and production builds fail when HIGH or CRITICAL findings exist
- Scan reports are archived in Jenkins

## Integration Points

- Jenkins shared library
- service Jenkinsfiles through library reuse
- Docker image build process
- Docker Hub push stage
- environment promotion flow

## Environment Behavior

- Build / PR: validation only, no deployment
- Dev: report-only security checks
- Staging: blocking security gate
- Prod: blocking security gate before production deployment