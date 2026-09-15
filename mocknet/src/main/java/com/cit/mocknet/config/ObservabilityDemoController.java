package com.cit.mocknet.config;

import com.cit.mocknet.model.QueueName;
import com.cit.mocknet.observability.OperationalTelemetry;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, authenticated controls present only in the local demo profile. */
@RestController
@Profile("observability-demo")
@RequestMapping("/api/demo")
public class ObservabilityDemoController {
    private final OperationalTelemetry telemetry;
    private final DataSource dataSource;
    private final String token;
    private final AtomicBoolean pressureActive = new AtomicBoolean();
    public ObservabilityDemoController(OperationalTelemetry telemetry, DataSource dataSource,
            @Value("${mocknet.demo.token:}") String token) {
        this.telemetry = telemetry; this.dataSource = dataSource; this.token = token;
    }
    private void authorize(String supplied) {
        if (token.isBlank() || supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    @PostMapping("/pause/{stage}")
    public Map<String,Object> pause(@RequestHeader(value="X-Demo-Token", required=false) String supplied,
            @PathVariable QueueName stage, @RequestParam(defaultValue="30") int seconds) {
        authorize(supplied);
        if (seconds < 0 || seconds > 120 || stage == QueueName.DEAD_LETTER) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        telemetry.pause(stage, seconds);
        return Map.of("stage",stage,"seconds",seconds,"autoResume",true);
    }
    @PostMapping("/database-pressure")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> pressure(@RequestHeader(value="X-Demo-Token", required=false) String supplied,
            @RequestParam(defaultValue="20") int seconds) {
        authorize(supplied);
        if (seconds < 1 || seconds > 30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (!pressureActive.compareAndSet(false,true)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        Thread worker = new Thread(() -> {
            List<Connection> held = new ArrayList<>();
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            try {
                int count = dataSource instanceof HikariDataSource hikari ? hikari.getMaximumPoolSize() : 1;
                for (int i=0; i<count && System.nanoTime()<deadline; i++) held.add(dataSource.getConnection());
                while (System.nanoTime()<deadline) Thread.sleep(100);
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Demo connection pressure interrupted",e); }
            finally { for (Connection c: held) try { c.close(); } catch (Exception ignored) {} pressureActive.set(false); }
        }, "demo-database-pressure");
        worker.setDaemon(true); worker.start();
        return Map.of("scenario","real Hikari connection exhaustion","seconds",seconds,"automaticRelease",true);
    }
}
