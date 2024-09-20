A backup provider for Rama that uses AWS S3.

# Usage

To use the provider, include the provider jar in your deployment jar.

Set the BACKUP-PROVIDER config to

`com.rpl.rama.backup.s3.S3BackupProvider <bucket-name>`

Replace `<bucket-name>` with the name of the bucket you wish to use.

It is advisable to create the bucket with the desired permissions and
other configuration.  However, the provider will try to create the
bucket if it does not exist.

# Credentials

The Rama s3-provider use the AWS [default provider chain][https://docs.aws.amazon.com/sdkref/latest/guide/standardized-credentials.html] to determine credentials.

The recommended way to provide credentials when running Rama on AWS is
to use [instance profiles][https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_use_switch-role-ec2_instance-profiles.html].

# Tests

The tests use a docker container to run adobe/s3mock, which provides a
mock of the Amazon S3 service.

On mac you may need to set DOCKER_HOST, e.g.

`export DOCKER_HOST=unix:///${HOME}/.docker/run/docker.sock`

To run integration tests

`mvn verify`
