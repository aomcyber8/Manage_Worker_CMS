package th.co.sena.workercms;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WorkerRecord(
    int row,
    String code,
    @JsonProperty("search_name") String searchName,
    @JsonProperty("termination_date") String terminationDate,
    String id,
    String no,
    String name,
    String email,
    String position,
    String department
) {}
