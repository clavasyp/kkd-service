package ru.kkd.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class ProbeResponseJsonTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void usesSnakeCaseKeepsNullAndWritesNewField() throws Exception {
        String json = mapper.writeValueAsString(
                new ProbeResponse(1, "Департамент", null, new ProbeResponse.Counters(2)));

        assertThat(json).isEqualTo(
                "{\"id\":1,\"display_name\":\"Департамент\",\"previous\":null,\"delta\":{\"new\":2}}");
    }
}
