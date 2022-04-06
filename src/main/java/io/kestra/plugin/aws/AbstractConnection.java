package io.kestra.plugin.aws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractConnection extends Task implements AbstractConnectionInterface {
    protected String accessKeyId;

    protected String secretKeyId;

    protected AwsCredentialsProvider credentials(RunContext runContext) throws IllegalVariableEvaluationException {
        String accessKeyId = runContext.render(this.accessKeyId);
        String secretKeyId = runContext.render(this.secretKeyId);

        if (accessKeyId != null && secretKeyId != null) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                accessKeyId,
                secretKeyId
            ));
        }

        return DefaultCredentialsProvider.builder()
            .build();
    }
}
