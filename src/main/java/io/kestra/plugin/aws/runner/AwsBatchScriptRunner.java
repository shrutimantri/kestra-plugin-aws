package io.kestra.plugin.aws.runner;

import com.google.common.collect.Iterables;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.script.*;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.ListUtils;
import io.kestra.plugin.aws.AbstractConnectionInterface;
import io.kestra.plugin.aws.ConnectionUtils;
import io.kestra.plugin.aws.s3.AbstractS3;
import io.micronaut.core.annotation.Introspected;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import reactor.core.CoreSubscriber;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.batch.BatchClient;
import software.amazon.awssdk.services.batch.BatchClientBuilder;
import software.amazon.awssdk.services.batch.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.kestra.core.utils.Rethrow.throwConsumer;

@Introspected
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "AWS Batch script runner", description = """
    Run a script in a container on an AWS Batch Compute Environment (Only Fargate or EC2 are supported; For EKS, Kubernetes Script Runner must be used).
    Upon worker restart, this job will be requeued and executed again. Moreover, the existing job will be kept running and handled by AWS Batch till this issue (https://github.com/kestra-io/plugin-aws/issues/402) is handled.
    To use `inputFiles`, `outputFiles` and `namespaceFiles` properties, you must provide a `s3Bucket`.
    Doing so will upload the files to the bucket before running the script and download them after the script execution.
    This runner will wait for the task to succeed or fail up to a max `waitUntilCompletion` duration.
    It will return with an exit code according to the following mapping:
    - SUCCEEDED: 0
    - FAILED: 1
    - RUNNING: 2
    - RUNNABLE: 3
    - PENDING: 4
    - STARTING: 5
    - SUBMITTED: 6
    - OTHER: -1""")
@Plugin(examples = {}, beta = true)
public class AwsBatchScriptRunner extends ScriptRunner implements AbstractS3, AbstractConnectionInterface, RemoteRunnerInterface {
    private static final Map<JobStatus, Integer> exitCodeByStatus = Map.of(
        JobStatus.FAILED, 1,
        JobStatus.RUNNING, 2,
        JobStatus.RUNNABLE, 3,
        JobStatus.PENDING, 4,
        JobStatus.STARTING, 5,
        JobStatus.SUBMITTED, 6,
        JobStatus.UNKNOWN_TO_SDK_VERSION, -1
    );

    @NotNull
    private String region;
    private String endpointOverride;

    // Configuration for StaticCredentialsProvider
    private String accessKeyId;
    private String secretKeyId;
    private String sessionToken;

    // Configuration for AWS STS AssumeRole
    private String stsRoleArn;
    private String stsRoleExternalId;
    private String stsRoleSessionName;
    private String stsEndpointOverride;
    @Builder.Default
    private Duration stsRoleSessionDuration = AbstractConnectionInterface.AWS_MIN_STS_ROLE_SESSION_DURATION;

    @Schema(
        title = "Compute environment on which to run the job."
    )
    @NotNull
    @PluginProperty(dynamic = true)
    private String computeEnvironmentArn;

    @Schema(
        title = "Job queue to use to submit jobs (ARN). If not specified, will create one which could lead to longer execution."
    )
    @PluginProperty(dynamic = true)
    private String jobQueueArn;

    @Schema(
        title = "S3 Bucket to use to upload (`inputFiles` and `namespaceFiles`) and download (`outputFiles`) files.",
        description = "It's mandatory to provide a bucket if you want to use such properties."
    )
    @PluginProperty(dynamic = true)
    private String bucket;

    @Schema(
        title = "Execution role to use to run the job.",
        description = "Mandatory if the compute environment is a Fargate one. See https://docs.aws.amazon.com/batch/latest/userguide/execution-IAM-role.html"
    )
    @PluginProperty(dynamic = true)
    private String executionRoleArn;

    @Schema(
        title = "Task role to use within the container.",
        description = "Needed if you want to be authentified to AWS CLI within your container. Otherwise, you will need to authenticate by your own or not use it."
    )
    @PluginProperty(dynamic = true)
    private String taskRoleArn;

    @Schema(
        title = "Container custom resources requests.",
        description = "If using a Fargate compute environments, resources requests must match this table: https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-cpu-memory-error.html"
    )
    @PluginProperty
    @NotNull
    @Builder.Default
    private Resources resources = Resources.builder()
        .request(
            Resource.builder()
                .memory("2048")
                .cpu("1")
                .build()
        ).build();

    @Schema(
        title = "The maximum duration to wait for the job completion. AWS Batch will automatically timeout the job upon reaching such duration and the task will be failed."
    )
    @Builder.Default
    private Duration waitUntilCompletion = Duration.ofHours(1);

    @Override
    public RunnerResult run(RunContext runContext, ScriptCommands scriptCommands, List<String> filesToUpload, List<String> filesToDownload) throws Exception {
        boolean hasS3Bucket = this.bucket != null;

        String renderedBucket = runContext.render(bucket);

        boolean hasFilesToUpload = !ListUtils.isEmpty(filesToUpload);
        if (hasFilesToUpload && !hasS3Bucket) {
            throw new IllegalArgumentException("You must provide a S3Bucket to use `inputFiles` or `namespaceFiles`");
        }
        boolean hasFilesToDownload = !ListUtils.isEmpty(filesToDownload);
        if (hasFilesToDownload && !hasS3Bucket) {
            throw new IllegalArgumentException("You must provide a S3Bucket to use `outputFiles`");
        }

        Logger logger = runContext.logger();
        AbstractLogConsumer logConsumer = scriptCommands.getLogConsumer();

        String renderedRegion = runContext.render(this.region);
        Region regionObject = Region.of(renderedRegion);
        BatchClientBuilder batchClientBuilder = BatchClient.builder()
            .credentialsProvider(ConnectionUtils.credentialsProvider(this.awsClientConfig(runContext)))
            .region(regionObject)
            // Use the httpClientBuilder to delegate the lifecycle management of the HTTP client to the AWS SDK
            .httpClientBuilder(serviceDefaults -> ApacheHttpClient.builder().build());

        if (this.endpointOverride != null) {
            batchClientBuilder.endpointOverride(URI.create(runContext.render(this.endpointOverride)));
        }

        BatchClient client = batchClientBuilder
            .build();

        String jobName = ScriptService.jobName(runContext);

        ComputeEnvironmentDetail computeEnvironmentDetail = client.describeComputeEnvironments(
                DescribeComputeEnvironmentsRequest.builder()
                    .computeEnvironments(computeEnvironmentArn)
                    .build()
            ).computeEnvironments().stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Compute environment not found: " + computeEnvironmentArn));

        String kestraVolume = "kestra";
        if (computeEnvironmentDetail.containerOrchestrationType() != OrchestrationType.ECS) {
            throw new IllegalArgumentException("Only ECS compute environments are supported");
        }

        PlatformCapability platformCapability = switch (computeEnvironmentDetail.computeResources().type()) {
            case FARGATE:
            case FARGATE_SPOT:
                yield PlatformCapability.FARGATE;
            case EC2:
            case SPOT:
                yield PlatformCapability.EC2;
            default:
                yield null;
        };

        String jobDefinitionName = IdUtils.create();
        RegisterJobDefinitionRequest.Builder jobDefBuilder = RegisterJobDefinitionRequest.builder()
            .jobDefinitionName(jobDefinitionName)
            .type(JobDefinitionType.CONTAINER)
            .tags(ScriptService.labels(runContext, "kestra-", true, true))
            .platformCapabilities(platformCapability);
        Path batchWorkingDirectory = (Path) this.additionalVars(runContext, scriptCommands).get(ScriptService.VAR_WORKING_DIR);

        if (hasFilesToUpload) {
            try (S3TransferManager transferManager = transferManager(runContext)) {
                filesToUpload.stream().map(relativePath ->
                        UploadFileRequest.builder()
                            .putObjectRequest(
                                PutObjectRequest
                                    .builder()
                                    .bucket(renderedBucket)
                                    // Use path to eventually deduplicate leading '/'
                                    .key((batchWorkingDirectory + Path.of("/" + relativePath).toString()).substring(1))
                                    .build()
                            )
                            .source(runContext.resolve(Path.of(relativePath)))
                            .build()
                    ).map(transferManager::uploadFile)
                    .map(FileUpload::completionFuture)
                    .forEach(throwConsumer(CompletableFuture::get));
            }
        }

        EcsTaskProperties.Builder taskPropertiesBuilder = EcsTaskProperties.builder()
            .volumes(
                Volume.builder()
                    .name(kestraVolume)
                    .build()
            );

        if (platformCapability == PlatformCapability.FARGATE) {
            taskPropertiesBuilder
                .networkConfiguration(
                    NetworkConfiguration.builder()
                        .assignPublicIp(AssignPublicIp.ENABLED)
                        .build()
                );
        }

        if (this.executionRoleArn != null) {
            taskPropertiesBuilder.executionRoleArn(runContext.render(this.executionRoleArn));
        }

        if (this.taskRoleArn != null) {
            taskPropertiesBuilder.taskRoleArn(runContext.render(this.taskRoleArn));
        }

        List<TaskContainerProperties> containers = new ArrayList<>();
        String inputFilesContainerName = "inputFiles";
        String mainContainerName = "main";
        String outputFilesContainerName = "outputFiles";

        int baseSideContainerMemory = 128;
        float baseSideContainerCpu = 0.1f;
        Map<String, Object> additionalVars = this.additionalVars(runContext, scriptCommands);
        Object s3WorkingDir = additionalVars.get(ScriptService.VAR_BUCKET_PATH);
        Path batchOutputDirectory = (Path) additionalVars.get(ScriptService.VAR_OUTPUT_DIR);
        MountPoint volumeMount = MountPoint.builder()
            .containerPath(batchWorkingDirectory.toString())
            .sourceVolume(kestraVolume)
            .build();

        if (hasS3Bucket) {
            containers.add(
                withResources(
                    TaskContainerProperties.builder()
                        .image("ghcr.io/kestra-io/awsbatch:latest")
                        .mountPoints(volumeMount)
                        .essential(false)
                        .command(ScriptService.scriptCommands(
                            List.of("/bin/sh", "-c"),
                            null,
                            Stream.concat(
                                ListUtils.emptyOnNull(filesToUpload).stream()
                                    .map(relativePath -> "aws s3 cp " + s3WorkingDir + Path.of("/" + relativePath) + " " + batchWorkingDirectory + Path.of("/" + relativePath)),
                                Stream.of("mkdir " + batchOutputDirectory)
                            ).toList()
                        ))
                        .name(inputFilesContainerName),
                    baseSideContainerMemory,
                    baseSideContainerCpu).build()
            );
        }

        int sideContainersMemoryAllocations = hasS3Bucket ? baseSideContainerMemory * 2 : 0;
        float sideContainersCpuAllocations = hasS3Bucket ? baseSideContainerCpu * 2 : 0;

        TaskContainerProperties.Builder mainContainerBuilder = withResources(
            TaskContainerProperties.builder()
                .image(scriptCommands.getContainerImage())
                .command(scriptCommands.getCommands())
                .name(mainContainerName)
                .logConfiguration(
                    LogConfiguration.builder()
                        .logDriver(LogDriver.AWSLOGS)
                        .options(Map.of("awslogs-stream-prefix", jobName))
                        .build()
                )
                .environment(
                    this.env(runContext, scriptCommands).entrySet().stream()
                        .map(e -> KeyValuePair.builder().name(e.getKey()).value(e.getValue()).build())
                        .toArray(KeyValuePair[]::new)
                )
                .essential(!hasS3Bucket),
            Integer.parseInt(resources.getRequest().getMemory()) - sideContainersMemoryAllocations,
            Float.parseFloat(resources.getRequest().getCpu()) - sideContainersCpuAllocations
        );

        if (hasS3Bucket) {
            mainContainerBuilder.dependsOn(TaskContainerDependency.builder().containerName(inputFilesContainerName).condition("SUCCESS").build());
            mainContainerBuilder.mountPoints(volumeMount);
        }

        containers.add(mainContainerBuilder.build());

        if (hasS3Bucket) {
            containers.add(
                withResources(
                    TaskContainerProperties.builder()
                        .image("ghcr.io/kestra-io/awsbatch:latest")
                        .mountPoints(volumeMount)
                        .command(ScriptService.scriptCommands(
                            List.of("/bin/sh", "-c"),
                            null,
                            Stream.concat(
                                filesToDownload.stream()
                                    .map(relativePath -> "aws s3 cp " + batchWorkingDirectory + "/" + relativePath + " " + s3WorkingDir + Path.of("/" + relativePath)),
                                Stream.of("aws s3 cp " + batchOutputDirectory + "/ " + s3WorkingDir + "/" + batchWorkingDirectory.relativize(batchOutputDirectory) + "/ --recursive")
                            ).toList()
                        ))
                        .dependsOn(TaskContainerDependency.builder().containerName(mainContainerName).condition("SUCCESS").build())
                        .name(outputFilesContainerName),
                    baseSideContainerMemory,
                    baseSideContainerCpu).build()
            );
        }

        taskPropertiesBuilder.containers(containers);

        jobDefBuilder.ecsProperties(
            EcsProperties.builder()
                .taskProperties(taskPropertiesBuilder.build())
                .build()
        );

        logger.debug("Registering job definition");
        RegisterJobDefinitionResponse registerJobDefinitionResponse = client.registerJobDefinition(jobDefBuilder.build());
        logger.debug("Job definition successfully registered: {}", jobDefinitionName);

        String jobQueue = runContext.render(this.jobQueueArn);
        if (jobQueue == null) {
            logger.debug("Job queue not specified, creating a one-use job queue");
            CreateJobQueueResponse jobQueueResponse = client.createJobQueue(
                CreateJobQueueRequest.builder()
                    .jobQueueName(IdUtils.create())
                    .priority(10)
                    .computeEnvironmentOrder(
                        ComputeEnvironmentOrder.builder()
                            .order(1)
                            .computeEnvironment(runContext.render(this.computeEnvironmentArn))
                            .build()
                    )
                    .build()
            );

            waitForQueueUpdate(client, jobQueueResponse);

            jobQueue = jobQueueResponse.jobQueueArn();
            logger.debug("Job queue created: {}", jobQueue);
        }

        String jobDefArn = registerJobDefinitionResponse.jobDefinitionArn();

        CloudWatchLogsAsyncClient cloudWatchLogsAsyncClient =
            CloudWatchLogsAsyncClient.builder()
                .credentialsProvider(ConnectionUtils.credentialsProvider(this.awsClientConfig(runContext)))
                .region(regionObject)
                .build();
        try {
            String logGroupName = "/aws/batch/job";
            String logGroupArn = getLogGroupArn(cloudWatchLogsAsyncClient, logGroupName, renderedRegion);

            if (logGroupArn == null) {
                cloudWatchLogsAsyncClient.createLogGroup(
                    CreateLogGroupRequest.builder()
                        .logGroupName(logGroupName)
                        .build()
                ).get();

                logGroupArn = getLogGroupArn(cloudWatchLogsAsyncClient, logGroupName, renderedRegion);
            }

            StartLiveTailRequest request = StartLiveTailRequest.builder()
                .logGroupIdentifiers(logGroupArn)
                .logStreamNamePrefixes(jobName)
                .build();

            cloudWatchLogsAsyncClient.startLiveTail(request, getStartLiveTailResponseStreamHandler(logConsumer));

            logger.debug("Submitting job to queue");
            SubmitJobResponse submitJobResponse = client.submitJob(
                SubmitJobRequest.builder()
                    .jobName(jobName)
                    .jobDefinition(jobDefArn)
                    .jobQueue(jobQueue)
                    .timeout(
                        JobTimeout.builder()
                            .attemptDurationSeconds((int) this.waitUntilCompletion.toSeconds())
                            .build()
                    )
                    .build()
            );
            logger.debug("Job submitted: {}", submitJobResponse.jobName());

            final AtomicReference<DescribeJobsResponse> describeJobsResponse = new AtomicReference<>();
            try {
                Await.until(() -> {
                    describeJobsResponse.set(client.describeJobs(
                        DescribeJobsRequest.builder()
                            .jobs(submitJobResponse.jobId())
                            .build()
                    ));

                    JobStatus status = describeJobsResponse.get().jobs().get(0).status();
                    if (status == JobStatus.FAILED) {
                        throw new RuntimeException();
                    }

                    return status == JobStatus.SUCCEEDED;
                }, Duration.ofMillis(500), this.waitUntilCompletion);
            } catch (TimeoutException | RuntimeException e) {
                JobStatus status = describeJobsResponse.get().jobs().get(0).status();
                Integer exitCode = exitCodeByStatus.get(status);
                logConsumer.accept("AWS Batch Job ended with status " + status.name() + ". Please check job with name " + jobName + " for more details.", true);
                throw new ScriptException(exitCode, logConsumer.getStdOutCount(), logConsumer.getStdErrCount());
            }

            if (hasS3Bucket) {
                try (S3TransferManager transferManager = transferManager(runContext)) {
                    filesToDownload.stream().map(relativePath -> transferManager.downloadFile(
                            DownloadFileRequest.builder()
                                .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(renderedBucket)
                                    .key((batchWorkingDirectory + "/" + relativePath).substring(1))
                                    .build())
                                .destination(scriptCommands.getWorkingDirectory().resolve(Path.of(relativePath.startsWith("/") ? relativePath.substring(1) : relativePath)))
                                .build()
                        )).map(FileDownload::completionFuture)
                        .forEach(throwConsumer(CompletableFuture::get));

                    transferManager.downloadDirectory(DownloadDirectoryRequest.builder()
                            .bucket(renderedBucket)
                            .destination(scriptCommands.getOutputDirectory())
                            .listObjectsV2RequestTransformer(builder -> builder
                                .prefix(batchOutputDirectory.toString().substring(1))
                            )
                            .build())
                        .completionFuture()
                        .get();
                }
            }
        } finally {
            cleanupBatchResources(client, jobQueue, jobDefArn);
            // Manual close after cleanup to make sure we get all remaining logs
            cloudWatchLogsAsyncClient.close();
            if (hasS3Bucket) {
                cleanupS3Resources(runContext, batchWorkingDirectory);
            }
        }

        return new RunnerResult(0, logConsumer);
    }

    @Override
    public Map<String, Object> runnerAdditionalVars(RunContext runContext, ScriptCommands scriptCommands) throws IllegalVariableEvaluationException {
        Map<String, Object> additionalVars = new HashMap<>();
        Path batchWorkingDirectory = Path.of("/" + IdUtils.create());
        additionalVars.put(ScriptService.VAR_WORKING_DIR, batchWorkingDirectory);

        if (this.bucket != null) {
            Path batchOutputDirectory = batchWorkingDirectory.resolve(IdUtils.create());
            additionalVars.put(ScriptService.VAR_OUTPUT_DIR, batchOutputDirectory);
            additionalVars.put(ScriptService.VAR_BUCKET_PATH, "s3://" + runContext.render(this.bucket) + batchWorkingDirectory);
        }

        return additionalVars;
    }

    @Nullable
    private static String getLogGroupArn(CloudWatchLogsAsyncClient cloudWatchLogsAsyncClient, String logGroupName, String renderedRegion) throws InterruptedException, ExecutionException {
        return cloudWatchLogsAsyncClient.describeLogGroups(
                DescribeLogGroupsRequest.builder()
                    .logGroupNamePrefix(logGroupName)
                    .build()
            ).get().logGroups().stream()
            .filter(logGroup -> logGroup.arn().contains(renderedRegion))
            .findFirst()
            .map(LogGroup::arn)
            .map(arn -> arn.endsWith("*") ? arn.substring(0, arn.length() - 1) : arn)
            .orElse(null);
    }

    private TaskContainerProperties.Builder withResources(TaskContainerProperties.Builder builder, Integer memory, Float cpu) {
        return builder
            .resourceRequirements(
                ResourceRequirement.builder()
                    .type(ResourceType.MEMORY)
                    .value(memory.toString())
                    .build(),
                ResourceRequirement.builder()
                    .type(ResourceType.VCPU)
                    .value(cpu.toString())
                    .build()
            );
    }

    private void cleanupS3Resources(RunContext runContext, Path batchWorkingDirectory) throws IllegalVariableEvaluationException {
        String renderedBucket = runContext.render(bucket);
        try (S3AsyncClient s3AsyncClient = asyncClient(runContext)) {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(renderedBucket)
                .prefix(batchWorkingDirectory.toString())
                .build();

            ListObjectsV2Response listResponse = s3AsyncClient.listObjectsV2(listRequest).get();
            List<ObjectIdentifier> objectsIdentifiers = listResponse.contents().stream()
                .map(S3Object::key)
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();
            StreamSupport.stream(Iterables.partition(
                    objectsIdentifiers,
                    1000
                ).spliterator(), false)
                .map(objects -> s3AsyncClient.deleteObjects(
                    DeleteObjectsRequest.builder()
                        .bucket(renderedBucket)
                        .delete(Delete.builder()
                            .objects(objects)
                            .build())
                        .build()
                )).forEach(throwConsumer(CompletableFuture::get));
        } catch (Exception e) {
            runContext.logger().warn("Error while cleaning up S3: {}", e.toString());
        }
    }

    private S3TransferManager transferManager(RunContext runContext) throws IllegalVariableEvaluationException {
        return S3TransferManager.builder()
            .s3Client(this.asyncClient(runContext))
            .build();
    }

    private static StartLiveTailResponseHandler getStartLiveTailResponseStreamHandler(AbstractLogConsumer logConsumer) {
        return StartLiveTailResponseHandler.builder()
            .onError(throwable -> {
                CloudWatchLogsException e = (CloudWatchLogsException) throwable.getCause();
                logConsumer.accept(e.awsErrorDetails().errorMessage(), true);
            })
            .subscriber(() -> new CoreSubscriber<>() {
                @Override
                public void onSubscribe(@NonNull Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(StartLiveTailResponseStream event) {
                    if (event instanceof LiveTailSessionStart) {
                        // do nothing
                    } else if (event instanceof LiveTailSessionUpdate sessionUpdate) {
                        List<LiveTailSessionLogEvent> logEvents = sessionUpdate.sessionResults();
                        logEvents.stream()
                            .<String>mapMulti((e, consumer) -> e.message()
                                .replaceAll("(?!^)(::\\{)", System.lineSeparator() + "::{")
                                .replace("\r", System.lineSeparator())
                                .lines()
                                .forEach(consumer)
                            ).forEach(message -> {
                                if (message.startsWith("::{")) {
                                    logConsumer.accept(message, false);
                                } else {
                                    logConsumer.accept("[JOB LOG] " + message, false);
                                }
                            });
                    } else {
                        throw CloudWatchLogsException.builder().message("Unknown event type").build();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    logConsumer.accept(throwable.getMessage(), true);
                }

                @Override
                public void onComplete() {
                    // no-op
                }
            })
            .build();
    }

    private static void waitForQueueUpdate(BatchClient client, CreateJobQueueResponse jobQueueResponse) throws TimeoutException {
        Await.until(() -> {
            DescribeJobQueuesResponse describeJobQueuesResponse = client.describeJobQueues(
                DescribeJobQueuesRequest.builder()
                    .jobQueues(jobQueueResponse.jobQueueArn())
                    .build()
            );

            return describeJobQueuesResponse.jobQueues().get(0).status() == JQStatus.VALID;
        }, Duration.ofMillis(500), Duration.ofMinutes(1));
    }

    private void cleanupBatchResources(BatchClient client, String jobQueue, String jobDefArn) throws TimeoutException {
        // We created one and should delete it at the end
        if (this.jobQueueArn == null) {
            client.updateJobQueue(
                UpdateJobQueueRequest.builder()
                    .jobQueue(jobQueue)
                    .state(JQState.DISABLED)
                    .build()
            );

            waitForQueueUpdate(client, CreateJobQueueResponse.builder().jobQueueArn(jobQueue).build());
            client.deleteJobQueue(
                DeleteJobQueueRequest.builder()
                    .jobQueue(jobQueue)
                    .build()
            );
        }

        client.deregisterJobDefinition(
            DeregisterJobDefinitionRequest.builder()
                .jobDefinition(jobDefArn)
                .build()
        );
    }

    @Getter
    @Builder
    public static class Resources {
        @NotNull
        private Resource request;
    }

    @Getter
    @Builder
    public static class Resource {
        @NotNull
        private String memory;
        @NotNull
        private String cpu;
    }
}
