# ISSS pilot deployment (ISSSDP-512)

Everything ISSS-specific lives under `isss/`; the rest of this repository mirrors upstream.

`openshift/deploy-pilot.sh` stands the dev pilot up in OpenShift Silver namespace `dabb52-tools`
in stages (`db`, `fam`, `lookup`, `status`): a dev-grade PostgreSQL (`postgres-dev.yml`), the secrets
FAM reads, upstream's `common/openshift.init.yml`, backend and frontend templates with the public
`ghcr.io/bcgov/nr-fam` images, and nr-user-lookup-api built in-cluster (`lookup-api-build.yml`) from
the branch carrying bcgov/nr-user-lookup-api#17. Credentials come from local files
(`~/.config/nr-fam/css.env`, `~/.raven/.env`) through temp files; nothing secret is in this folder.

First run on 2026-10-15: route `https://nr-fam-isss-dev.apps.silver.devops.gov.bc.ca`, one replica of
each component, about 300m CPU / 1.1Gi memory of the namespace's 500m / 2Gi request quota.
