package th.co.sena.workercms;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WorkerResult(
    int row,
    String code,
    String name,
    @JsonProperty("search_name") String searchName,
    WorkerStatus status,
    String message
) {}
