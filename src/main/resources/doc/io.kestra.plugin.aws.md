### Authentication

All tasks must be authenticated for the AWS Platform. You can either setup the credentials using DefaultCredentialsProvider or using appropriate secret variables.

## DefaultCredentialsProvider

DefaultCredentialsProvider is an AWS credentials provider chain that looks for credentials in this order:
- **Java System Properties** - Java system properties with variables `aws.accessKeyId` and `aws.secretAccessKey`.
- **Environment Variables** - `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`.
- Web Identity Token credentials from system properties or environment variables.
- Credential profiles file at the default location (~/.aws/credentials) shared by all AWS SDKs and the AWS CLI.
- Credentials delivered through the Amazon EC2 container service if `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI` environment variable is set and security manager has permission to access the variable.
- Instance profile credentials delivered through the Amazon EC2 metadata service.

## Using Secret Variables

In the Docker environment file, you can set the secret variables: `SECRET_AWS_ACCESS_KEY_ID` and `SECRET_AWS_SECRET_KEY_ID`, 
and assign them the corresponding values in base640-encoded format. These variables can later be accessed in the task file using
`{{ secret('AWS_ACCESS_KEY_ID') }}` and `{{ secret('AWS_SECRET_KEY_ID') }}` respectively.
