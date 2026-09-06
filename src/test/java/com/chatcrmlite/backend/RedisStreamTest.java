package com.chatcrmlite.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import java.util.Collections;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
public class RedisStreamTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    public void testStream() {
        MapRecord<String, String, String> record = StreamRecords.newRecord().in("test-stream").ofMap(Collections.singletonMap("payload", "{\"test\": \"payload\"}"));
        redisTemplate.opsForStream().add(record);
        System.out.println("Added record to test-stream");
    }
}
